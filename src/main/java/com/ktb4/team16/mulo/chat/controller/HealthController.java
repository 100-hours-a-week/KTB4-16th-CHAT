package com.ktb4.team16.mulo.chat.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<
                    com.ktb4.team16.mulo.chat.delivery.ChatDeliveryLifecycle>
            lifecycle;

    @GetMapping("/health")
    public ResponseEntity<Void> health() {
        var state = lifecycle == null ? null : lifecycle.getIfAvailable();
        return state != null && state.isDraining()
                ? ResponseEntity.status(503).build()
                : ResponseEntity.ok().build();
    }
}
