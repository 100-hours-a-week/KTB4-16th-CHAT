package com.ktb4.team16.mulo.chat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(properties = "chat.websocket.allowed-origins=http://localhost")
@EnabledIfEnvironmentVariable(named = "CHAT_DB_URL", matches = ".+")
class MuloChatApplicationTests {

    @Test
    void contextLoads() {
    }

}
