package com.lewin.luxanaipark.entity;

import lombok.Data;

import java.util.List;

/**
 * @author Jun
 * @since 1.0.0
 */
@Data
public class Scene {

    private String name;

    private Integer capacity;

    private List<CameraInfo> cameraInfoList;
}
