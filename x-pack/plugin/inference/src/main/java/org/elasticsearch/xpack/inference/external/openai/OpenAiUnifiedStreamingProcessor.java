/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.inference.external.openai;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.elasticsearch.common.xcontent.ChunkedToXContent;
import org.elasticsearch.common.xcontent.LoggingDeprecationHandler;
import org.elasticsearch.xcontent.XContentFactory;
import org.elasticsearch.xcontent.XContentParser;
import org.elasticsearch.xcontent.XContentParserConfiguration;
import org.elasticsearch.xcontent.XContentType;
import org.elasticsearch.xpack.core.inference.results.StreamingUnifiedChatCompletionResults;
import org.elasticsearch.xpack.inference.common.DelegatingProcessor;
import org.elasticsearch.xpack.inference.external.response.streaming.ServerSentEvent;
import org.elasticsearch.xpack.inference.external.response.streaming.ServerSentEventField;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

import static org.elasticsearch.common.xcontent.XContentParserUtils.ensureExpectedToken;
import static org.elasticsearch.common.xcontent.XContentParserUtils.parseList;
import static org.elasticsearch.xpack.inference.external.response.XContentUtils.consumeUntilObjectEnd;
import static org.elasticsearch.xpack.inference.external.response.XContentUtils.moveToFirstToken;
import static org.elasticsearch.xpack.inference.external.response.XContentUtils.positionParserAtTokenAfterField;

/**
 * Parses the OpenAI chat completion streaming responses.
 * For a request like:
 *
 * <pre>
 *     <code>
 *         {
 *             "inputs": ["Please summarize this text: some text", "Answer the following question: Question"]
 *         }
 *     </code>
 * </pre>
 *
 * The response would look like:
 *
 * <pre>
 *     <code>
 *         {
 *              "id": "chatcmpl-123",
 *              "object": "chat.completion",
 *              "created": 1677652288,
 *              "model": "gpt-3.5-turbo-0613",
 *              "system_fingerprint": "fp_44709d6fcb",
 *              "choices": [
 *                  {
 *                      "index": 0,
 *                      "delta": {
 *                          "content": "\n\nHello there, how ",
 *                      },
 *                      "finish_reason": ""
 *                  }
 *              ]
 *          }
 *
 *         {
 *              "id": "chatcmpl-123",
 *              "object": "chat.completion",
 *              "created": 1677652288,
 *              "model": "gpt-3.5-turbo-0613",
 *              "system_fingerprint": "fp_44709d6fcb",
 *              "choices": [
 *                  {
 *                      "index": 1,
 *                      "delta": {
 *                          "content": "may I assist you today?",
 *                      },
 *                      "finish_reason": ""
 *                  }
 *              ]
 *          }
 *
 *         {
 *              "id": "chatcmpl-123",
 *              "object": "chat.completion",
 *              "created": 1677652288,
 *              "model": "gpt-3.5-turbo-0613",
 *              "system_fingerprint": "fp_44709d6fcb",
 *              "choices": [
 *                  {
 *                      "index": 2,
 *                      "delta": {},
 *                      "finish_reason": "stop"
 *                  }
 *              ]
 *          }
 *
 *          [DONE]
 *     </code>
 * </pre>
 */
public class OpenAiUnifiedStreamingProcessor extends DelegatingProcessor<Deque<ServerSentEvent>, ChunkedToXContent> {
    private static final Logger log = LogManager.getLogger(OpenAiUnifiedStreamingProcessor.class);
    private static final String FAILED_TO_FIND_FIELD_TEMPLATE = "Failed to find required field [%s] in OpenAI chat completions response";

    private static final String CHOICES_FIELD = "choices";
    private static final String DELTA_FIELD = "delta";
    private static final String CONTENT_FIELD = "content";
    private static final String DONE_MESSAGE = "[done]";
    private static final String REFUSAL_FIELD = "refusal";
    private static final String TOOL_CALLS_FIELD = "tool_calls";

    @Override
    protected void next(Deque<ServerSentEvent> item) throws Exception {
        var parserConfig = XContentParserConfiguration.EMPTY.withDeprecationHandler(LoggingDeprecationHandler.INSTANCE);

        var results = new ArrayDeque<StreamingUnifiedChatCompletionResults.Result>(item.size());
        for (ServerSentEvent event : item) {
            if (ServerSentEventField.DATA == event.name() && event.hasValue()) {
                try {
                    var delta = parse(parserConfig, event);
                    delta.forEachRemaining(results::offer);
                } catch (Exception e) {
                    log.warn("Failed to parse event from inference provider: {}", event);
                    throw e;
                }
            }
        }

        if (results.isEmpty()) {
            upstream().request(1);
        } else {
            downstream().onNext(new StreamingUnifiedChatCompletionResults.Results(results));
        }
    }

    private Iterator<StreamingUnifiedChatCompletionResults.Result> parse(XContentParserConfiguration parserConfig, ServerSentEvent event)
        throws IOException {
        if (DONE_MESSAGE.equalsIgnoreCase(event.value())) {
            return Collections.emptyIterator();
        }

        System.out.println(event.value());
        try (XContentParser jsonParser = XContentFactory.xContent(XContentType.JSON).createParser(parserConfig, event.value())) {
            moveToFirstToken(jsonParser);

            XContentParser.Token token = jsonParser.currentToken();
            ensureExpectedToken(XContentParser.Token.START_OBJECT, token, jsonParser);

            positionParserAtTokenAfterField(jsonParser, CHOICES_FIELD, FAILED_TO_FIND_FIELD_TEMPLATE);

            return parseList(jsonParser, parser -> {
                ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.currentToken(), parser);

                positionParserAtTokenAfterField(parser, DELTA_FIELD, FAILED_TO_FIND_FIELD_TEMPLATE);

                var currentToken = parser.currentToken();

                ensureExpectedToken(XContentParser.Token.START_OBJECT, currentToken, parser);

                String content = null;
                String refusal = null;
                List<StreamingUnifiedChatCompletionResults.ToolCall> toolCalls = new ArrayList<>();

                currentToken = parser.nextToken();

                // continue until the end of delta
                while (currentToken != null && currentToken != XContentParser.Token.END_OBJECT) {
                    if (currentToken == XContentParser.Token.START_OBJECT || currentToken == XContentParser.Token.START_ARRAY) {
                        parser.skipChildren();
                    }

                    if (currentToken == XContentParser.Token.FIELD_NAME) {
                        switch (parser.currentName()) {
                            case CONTENT_FIELD:
                                parser.nextToken();
                                if (parser.currentToken() == XContentParser.Token.VALUE_STRING) {
                                    content = parser.text();
                                }
                                // ensureExpectedToken(XContentParser.Token.VALUE_STRING, parser.currentToken(), parser);
                                break;
                            case REFUSAL_FIELD:
                                parser.nextToken();
                                if (parser.currentToken() == XContentParser.Token.VALUE_STRING) {
                                    refusal = parser.text();
                                }
                                // ensureExpectedToken(XContentParser.Token.VALUE_STRING, parser.currentToken(), parser);
                                break;
                            case TOOL_CALLS_FIELD:
                                parser.nextToken();
                                ensureExpectedToken(XContentParser.Token.START_ARRAY, parser.currentToken(), parser);
                                toolCalls = parseToolCalls(parser);
                                break;
                        }
                    }

                    currentToken = parser.nextToken();
                }

                // consumeUntilObjectEnd(parser); // end delta
                consumeUntilObjectEnd(parser); // end choices

                return new StreamingUnifiedChatCompletionResults.Result(content, refusal, toolCalls);
            }).stream().filter(Objects::nonNull).iterator();
        }
    }

    private List<StreamingUnifiedChatCompletionResults.ToolCall> parseToolCalls(XContentParser parser) throws IOException {
        List<StreamingUnifiedChatCompletionResults.ToolCall> toolCalls = new ArrayList<>();
        while (parser.nextToken() != XContentParser.Token.END_ARRAY) {
            ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.currentToken(), parser);
            int index = -1;
            String id = null;
            String functionName = null;
            String functionArguments = null;

            while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                if (parser.currentToken() == XContentParser.Token.FIELD_NAME) {
                    switch (parser.currentName()) {
                        case "index":
                            parser.nextToken();
                            ensureExpectedToken(XContentParser.Token.VALUE_NUMBER, parser.currentToken(), parser);
                            index = parser.intValue();
                            break;
                        case "id":
                            parser.nextToken();
                            ensureExpectedToken(XContentParser.Token.VALUE_STRING, parser.currentToken(), parser);
                            id = parser.text();
                            break;
                        case "function":
                            parser.nextToken();
                            ensureExpectedToken(XContentParser.Token.START_OBJECT, parser.currentToken(), parser);
                            while (parser.nextToken() != XContentParser.Token.END_OBJECT) {
                                if (parser.currentToken() == XContentParser.Token.FIELD_NAME) {
                                    switch (parser.currentName()) {
                                        case "name":
                                            parser.nextToken();
                                            ensureExpectedToken(XContentParser.Token.VALUE_STRING, parser.currentToken(), parser);
                                            functionName = parser.text();
                                            break;
                                        case "arguments":
                                            parser.nextToken();
                                            ensureExpectedToken(XContentParser.Token.VALUE_STRING, parser.currentToken(), parser);
                                            functionArguments = parser.text();
                                            break;
                                    }
                                }
                            }
                            break;
                    }
                }
            }
            toolCalls.add(new StreamingUnifiedChatCompletionResults.ToolCall(index, id, functionName, functionArguments));
        }
        return toolCalls;
    }
}
