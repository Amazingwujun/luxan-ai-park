package com.lewin.luxanaipark.entity;

import com.lewin.net.RemoteServerProperties;
import lombok.Data;

import java.time.Duration;

/**
 * 相机信息
 *
 * @author Jun
 * @since 1.0.0
 */
@Data
public class CameraInfo {

    private String name;

    private String ip;

    private Integer port = 5006;

    private String location;

    private String uname = "admin";

    private String passwd = "admin";

    public RemoteServerProperties transferTo() {
        if (ip == null || port == null || location == null || uname == null || passwd == null) {
            throw new IllegalArgumentException("非法的相机信息: " + this);
        }

        return new RemoteServerProperties(ip, port, Duration.ofSeconds(3), Duration.ofSeconds(5));
    }

    public String key(){
        return String.format("%s:%s", ip, port);
    }


}
