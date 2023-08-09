package com.lewin.luxanaipark;

import org.junit.jupiter.api.Test;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.TriggerBuilder;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

//@SpringBootTest
class LuxanAiParkApplicationTests {

    @Test
    void contextLoads() throws InterruptedException {
        var f = new CompletableFuture<Boolean>();
        f.orTimeout(1, TimeUnit.SECONDS);

        f.whenComplete((b, t) -> {

            System.out.println(t);
        });


        TimeUnit.HOURS.sleep(1);

        TriggerBuilder.newTrigger()
                .withIdentity("")
                .withSchedule(CronScheduleBuilder.cronSchedule(""))
                .build();
    }

}
