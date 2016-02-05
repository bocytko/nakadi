package de.zalando.aruha.nakadi.controller;

import com.codahale.metrics.annotation.Timed;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import de.zalando.aruha.nakadi.NakadiException;
import de.zalando.aruha.nakadi.domain.Cursor;
import de.zalando.aruha.nakadi.repository.EventConsumer;
import de.zalando.aruha.nakadi.repository.TopicRepository;
import de.zalando.aruha.nakadi.service.EventStream;
import de.zalando.aruha.nakadi.service.EventStreamConfig;
import de.zalando.aruha.nakadi.utils.FlushableGZIPOutputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.zalando.problem.Problem;

import javax.annotation.Nullable;
import javax.servlet.http.HttpServletResponse;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static javax.ws.rs.core.Response.Status.BAD_REQUEST;
import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.Status.PRECONDITION_FAILED;
import static javax.ws.rs.core.Response.Status.SERVICE_UNAVAILABLE;
import static org.zalando.problem.MoreStatus.UNPROCESSABLE_ENTITY;

@RestController
public class EventStreamController {

    private static final Logger LOG = LoggerFactory.getLogger(EventStreamController.class);

    private final TopicRepository topicRepository;

    private final ObjectMapper jsonMapper;

    public EventStreamController(final TopicRepository topicRepository, final ObjectMapper jsonMapper) {
        this.topicRepository = topicRepository;
        this.jsonMapper = jsonMapper;
    }

    @Timed(name = "stream_events_for_event_type", absolute = true)
    @RequestMapping(value = "/event-types/{name}/events", method = RequestMethod.GET)
    public StreamingResponseBody streamEventsFromPartition(
            @PathVariable("name") final String eventTypeName,
            @RequestParam(value = "batch_limit", required = false, defaultValue = "1") final int batchLimit,
            @RequestParam(value = "stream_limit", required = false, defaultValue = "0") final int streamLimit,
            @RequestParam(value = "batch_flush_timeout", required = false, defaultValue = "30") final int batchTimeout,
            @RequestParam(value = "stream_timeout", required = false, defaultValue = "0") final int streamTimeout,
            @RequestParam(value = "stream_keep_alive_limit", required = false, defaultValue = "0") final int streamKeepAliveLimit,
            @Nullable @RequestHeader(name = "X-Flow-Id", required = false) final String flowId,
            @Nullable @RequestHeader(name = "X-nakadi-cursors", required = false) final String cursorsStr,
            final NativeWebRequest request, final HttpServletResponse response) throws IOException {

        LOG.trace("starting event stream for flow id: {}", flowId);

        return outputStream -> {
            try {
                // todo: we should get topic from EventType after persistence of EventType is implemented
                @SuppressWarnings("UnnecessaryLocalVariable")
                final String topic = eventTypeName;

                // validate parameters
                if (!topicRepository.topicExists(topic)) {
                    writeProblemResponse(response, outputStream, NOT_FOUND, "topic not found");
                    return;
                }
                if (streamLimit != 0 && streamLimit < batchLimit) {
                    writeProblemResponse(response, outputStream, UNPROCESSABLE_ENTITY,
                            "stream_limit can't be lower than batch_limit");
                    return;
                }
                if (streamTimeout != 0 && streamTimeout < batchTimeout) {
                    writeProblemResponse(response, outputStream, UNPROCESSABLE_ENTITY,
                            "stream_timeout can't be lower than batch_flush_timeout");
                    return;
                }

                // deserialize cursors
                List<Cursor> cursors = null;
                if (cursorsStr != null) {
                    try {
                        cursors = jsonMapper.<List<Cursor>>readValue(cursorsStr,
                                new TypeReference<ArrayList<Cursor>>() {
                                });
                    } catch (IOException e) {
                        writeProblemResponse(response, outputStream, BAD_REQUEST,
                                "incorrect syntax of X-nakadi-cursors header");
                        return;
                    }
                }

                // check that offsets are not out of bounds and partitions exist
                if (cursors != null && !topicRepository.areCursorsValid(topic, cursors)) {
                    writeProblemResponse(response, outputStream, PRECONDITION_FAILED, "cursors are not valid");
                    return;
                }

                // if no cursors provided - read from the newest available events
                if (cursors == null) {
                    cursors = topicRepository
                            .listPartitions(topic)
                            .stream()
                            .map(pInfo -> new Cursor(pInfo.getPartitionId(), pInfo.getNewestAvailableOffset()))
                            .collect(Collectors.toList());
                }

                final Map<String, String> streamCursors = cursors
                        .stream()
                        .collect(Collectors.toMap(
                                Cursor::getPartition,
                                Cursor::getOffset));

                final EventStreamConfig streamConfig = new EventStreamConfig(topic, streamCursors, batchLimit,
                        streamLimit, batchTimeout, streamTimeout, streamKeepAliveLimit);

                response.setStatus(HttpStatus.OK.value());

                final String acceptEncoding = request.getHeader("Accept-Encoding");
                final boolean gzipEnabled = acceptEncoding != null && acceptEncoding.contains("gzip");
                final OutputStream output = gzipEnabled ? new FlushableGZIPOutputStream(outputStream) : outputStream;

                if (gzipEnabled) {
                    response.addHeader("Content-Encoding", "gzip");
                }

                final EventConsumer eventConsumer = topicRepository.createEventConsumer(topic,
                        streamConfig.getCursors());
                final EventStream eventStream = new EventStream(eventConsumer, output, streamConfig);
                eventStream.streamEvents();

                if (gzipEnabled) {
                    output.close();
                }

            }
            catch (final NakadiException e) {
                writeProblemResponse(response, outputStream, SERVICE_UNAVAILABLE, e.getProblemMessage());
            }
            catch (final Exception e) {
                writeProblemResponse(response, outputStream, INTERNAL_SERVER_ERROR, e.getMessage());
            }
            finally {
                outputStream.flush();
                outputStream.close();
            }
        };
    }

    private void writeProblemResponse(final HttpServletResponse response, final OutputStream outputStream,
                                      final Response.StatusType statusCode, final String message) throws IOException {
        response.setStatus(statusCode.getStatusCode());
        jsonMapper.writer().writeValue(outputStream, Problem.valueOf(statusCode, message));
    }
}
