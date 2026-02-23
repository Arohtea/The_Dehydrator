package com.arohtea.business_service.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequestMapping("/api/analysis")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class StreamController {

    private final RedisMessageListenerContainer listenerContainer;

    @GetMapping(value = "/stream/{taskId}", produces = "text/event-stream")
    public SseEmitter stream(@PathVariable("taskId") String taskId) {
        SseEmitter emitter = new SseEmitter(600_000L);
        ChannelTopic topic = new ChannelTopic("analysis:stream:" + taskId);

        MessageListener listener = (Message message, byte[] pattern) -> {
            try {
                String body = new String(message.getBody(), StandardCharsets.UTF_8);
                emitter.send(SseEmitter.event().data(body));
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        };

        listenerContainer.addMessageListener(listener, topic);

        Runnable cleanup = () -> listenerContainer.removeMessageListener(listener, topic);
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(t -> cleanup.run());

        return emitter;
    }
}
