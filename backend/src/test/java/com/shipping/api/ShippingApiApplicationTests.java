package com.shipping.api;

import com.shipping.api.repository.EmailRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "spring.profiles.active=no-db")
class ShippingApiApplicationTests {
    @Autowired ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context.getBeansOfType(EmailRepository.class)).isEmpty();
    }
}
