package com.ktb4.team16.mulo.chat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "chat.websocket.allowed-origins=http://localhost")
class MuloChatApplicationTests {

    @Test
    void contextLoads() {
    }

}
