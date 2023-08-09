package com.lewin.luxanaipark;

import com.lewin.commons.LewinApp;
import com.lewin.commons.LewinProperties;
import com.lewin.luxanaipark.config.BizProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;

@EnableConfigurationProperties({LewinProperties.class, BizProperties.class})
@SpringBootApplication
public class LuxanAiParkApplication {

    public static void main(String[] args) {
        LewinApp.run(LuxanAiParkApplication.class, args);
    }

}
