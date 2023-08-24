package com.lewin.luxanaipark.jna;

import com.lewin.commons.entity.Tuple2;
import com.lewin.luxanaipark.config.BizProperties;
import com.lewin.luxanaipark.entity.CameraInfo;
import com.lewin.luxanaipark.service.impl.HCNetServiceImpl;
import com.lewin.luxanaipark.utils.CommonUtils;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 异常回调函数
 *
 * @author Jun
 * @since 1.0.0
 */
@Slf4j
@Component
public class CustomExceptionCallBack implements HCNetSDK.FExceptionCallBack {

    private final Set<Integer> offlineUserIdSet = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, CameraInfo> allDeviceInfoMap;

    public CustomExceptionCallBack(BizProperties bizProperties) {
        this.allDeviceInfoMap = bizProperties.getSceneList().stream()
                .flatMap(scene -> scene.getCameraInfoList().stream())
                .collect(Collectors.toMap(CameraInfo::getSn, Function.identity()));

        HCNetServiceImpl.SCHEDULED_EXECUTOR.scheduleAtFixedRate(this::onlineStatusNotice, 1, 10, TimeUnit.SECONDS);
    }

    @Override
    public void invoke(int dwType, NativeLong lUserID, NativeLong lHandle, Pointer pUser) {
        final var userId = lUserID.intValue();

        var t2 = HCNetServiceImpl.USER_ID_AND_DEVICE_INFO_MAP.get(userId);
        switch (dwType) {
            case 0x8000, 0x8006 -> {
                // EXCEPTION_EXCHANGE 用户交互时异常（注册心跳超时，心跳间隔为2分钟） 8000
                // EXCEPTION_ALARMRECONNECT 报警时重连 8006
                // 处理设备的掉线
                offlineUserIdSet.add(userId);

                log.info(
                        "异常类型[{}] USER_ID[{}] 设备[{}:{}]离线",
                        Integer.toHexString(dwType),
                        userId,
                        t2.t0(),
                        CommonUtils.trimSerialNumber(t2.t1().sSerialNumber)
                );
            }
            case 0x8016, 0x8017 -> {
                // ALARM_RECONNECTSUCCESS 0x8016 报警时重连成功
                // RESUME_EXCHANGE 0x8017 用户交互恢复
                offlineUserIdSet.remove(userId);

                log.info("异常类型[{}] USER_ID[{}] 设备[{}:{}]上线",
                        Integer.toHexString(dwType),
                        userId,
                        t2.t0(),
                        CommonUtils.trimSerialNumber(t2.t1().sSerialNumber)
                );
            }
            default -> {
                log.warn(
                        "异常类型[{}] USER_ID[{}]",
                        Integer.toHexString(dwType),
                        userId
                );
            }
        }
    }

    private void onlineStatusNotice() {
        // 两种可能设备不在线
        // 1 设备未注册.(注册失败)
        // 2 设备注册后，各种原因离线

        // 1
        Set<String> allreadyLoginDeviceSnSet = HCNetServiceImpl.USER_ID_AND_DEVICE_INFO_MAP.values().stream().map(Tuple2::t1)
                .map(t -> t.sSerialNumber)
                .map(CommonUtils::trimSerialNumber)
                .collect(Collectors.toSet());
        for (var sn : allDeviceInfoMap.keySet()) {
            if (!allreadyLoginDeviceSnSet.contains(sn)) {
                // 未登录设备
                var connectInfo = allDeviceInfoMap.get(sn);
                log.debug("设备[{}]未注册, ip[{}:{}]", sn, connectInfo.getIp(), connectInfo.getPort());
            }
        }

        // 已注册设备处理
        for (var userId : HCNetServiceImpl.USER_ID_AND_DEVICE_INFO_MAP.keySet()) {
            var tuple2 = HCNetServiceImpl.USER_ID_AND_DEVICE_INFO_MAP.get(userId);
            var deviceInfo = tuple2.t1();
            if (offlineUserIdSet.contains(userId)) {
                log.debug("设备[{}]离线, ip[{}]", CommonUtils.trimSerialNumber(deviceInfo.sSerialNumber), tuple2.t0());
            } else {
                log.debug("设备[{}]在线, ip[{}]", CommonUtils.trimSerialNumber(deviceInfo.sSerialNumber), tuple2.t0());

                // todo 发送在线状态
            }
        }
    }
}
