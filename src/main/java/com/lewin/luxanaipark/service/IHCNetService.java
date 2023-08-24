package com.lewin.luxanaipark.service;

import com.lewin.luxanaipark.entity.CameraInfo;
import com.lewin.luxanaipark.jna.HCNetSDK;

/**
 * 海康设备网络 SDK 服务封装接口.
 *
 * @author Jun
 * @since 1.0.0
 */
public interface IHCNetService {

    /**
     * 初始化 {@link com.lewin.luxanaipark.jna.HCNetSDK}
     */
    void init();

    /**
     * 登录到指定设备
     *
     * @param cameraInfo 相机基础连接信息
     */
    void loginAndStartDeploy(CameraInfo cameraInfo);

    HCNetSDK sdk();

    Integer findUserId(String key);
}
