package de.zalando.aruha.nakadi.controller;

import com.codahale.metrics.annotation.Timed;
import de.zalando.aruha.nakadi.NakadiException;
import de.zalando.aruha.nakadi.repository.TopicRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.http.ResponseEntity.ok;
import static org.springframework.http.ResponseEntity.status;

@RestController
@RequestMapping(value = "/topics")
public class TopicsController {

    private static final Logger LOG = LoggerFactory.getLogger(TopicsController.class);

    @Autowired
    private TopicRepository topicRepository;

    @Timed(name = "get_topics", absolute = true)
    @RequestMapping(method = RequestMethod.GET)
    public ResponseEntity<?> listTopics(@RequestHeader(name = "X-Flow-Id", required = false) String flowId) {
        LOG.trace("Get topics endpoint is called for flow id: {}", flowId);
        try {
            return ok().body(topicRepository.listTopics());
        }
        catch (final NakadiException e) {
            return status(HttpStatus.SERVICE_UNAVAILABLE).body(e.getProblemMessage());
        }
    }

    @Timed(name = "post_event_to_partition", absolute = true)
    @RequestMapping(value = "/{topicId}/partitions/{partitionId}/events", method = RequestMethod.POST)
    public ResponseEntity<?> postEventToPartition(@PathVariable("topicId") final String topicId,
            @PathVariable("partitionId") final String partitionId, @RequestBody final String messagePayload) {
        LOG.trace("Event received: {}, {}, {}", topicId, partitionId, messagePayload);
        try {
            topicRepository.postEvent(topicId, partitionId, messagePayload);
            return status(HttpStatus.CREATED).build();
        } catch (final NakadiException e) {
            LOG.error("error posting to partition", e);
            return status(500).body(e.getProblemMessage());
        }
    }

}
