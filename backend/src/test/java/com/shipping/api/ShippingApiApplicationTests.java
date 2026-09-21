package com.shipping.api;

import com.shipping.api.repository.EmailRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
    "spring.profiles.active=test", "spring.datasource.url=jdbc:h2:mem:context;MODE=MySQL",
    "spring.datasource.driver-class-name=org.h2.Driver", "spring.datasource.username=sa", "spring.datasource.password="
})
class ShippingApiApplicationTests {
    @Autowired ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context.getBeansOfType(EmailRepository.class)).hasSize(1);
    }
}
