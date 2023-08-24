package com.lewin.luxanaipark.jna;

import com.lewin.commons.entity.Tuple4;
import com.lewin.commons.entity.Tuples;
import com.lewin.luxanaipark.service.impl.HCNetServiceImpl;
import com.lewin.luxanaipark.utils.CommonUtils;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

/**
 * 定制化报警回调函数
 *
 * @author Jun
 * @since 1.0.0
 */
@Slf4j
@Component
public class CustomWarningCallback implements HCNetSDK.FMSGCallBack, Runnable {

    private final BlockingQueue<Tuple4<Integer, HCNetSDK.NET_DVR_ALARMER, Pointer, Integer>> queue = new ArrayBlockingQueue<>(100_000);

    private CustomWarningCallback() {
        new Thread(this).start();
    }

    @Override
    public void invoke(NativeLong lCommand, HCNetSDK.NET_DVR_ALARMER pAlarmer,
                       Pointer pAlarmInfo, int dwBufLen, Pointer pUser) {
        try {
            queue.add(Tuples.of(lCommand.intValue(), pAlarmer, pAlarmInfo, dwBufLen));
        } catch (IllegalStateException e) {
            log.error(e.getMessage(), e);
        }
    }

    @Override
    public void run() {
        //noinspection InfiniteLoopStatement
        while (true) {
            try {
                var poll = queue.poll();
                if (poll == null) {
                    poll = queue.take();
                }

                final var command = poll.t0();
                final var pAlarmer = poll.t1();
                final var pAlarmInfo = poll.t2();
                final var deviceSn = CommonUtils.trimSerialNumber(pAlarmer.sSerialNumber);

                if (command == 0x1103) {
                    var alarm = new HCNetSDK.NET_DVR_PDC_ALRAM_INFO();
                    pointer2Structure(pAlarmInfo, alarm);

                    // 通过 deviceSn 找到 traffic
                    HCNetServiceImpl.HIK_TRAFFIC_INFO_MAP.values()
                            .stream()
                            .flatMap(Collection::stream)
                            .filter(t -> deviceSn.equals(t.t0().getSn()))
                            .findFirst()
                            .ifPresentOrElse(t -> {
                                t.t1().setIn(alarm.dwEnterNum).setOut(alarm.dwLeaveNum);
                            }, () -> {
                                log.error("deviceSn[{}] 无法关联到 HCNetServiceImpl.HIK_TRAFFIC_INFO_MAP", deviceSn);
                            });

                    log.info("进[{}] 出[{}]", alarm.dwEnterNum, alarm.dwLeaveNum);
                } else {
                    log.warn("设备[{}]事件[{}]被忽略", deviceSn, Integer.toHexString(command));
                }
            } catch (Throwable throwable) {
                log.error(throwable.getMessage(), throwable);
            }
        }
    }

    private void pointer2Structure(Pointer p, Structure struct) {
        struct.write();
        var size = struct.size();
        var structPointer = struct.getPointer();
        structPointer.write(0, p.getByteArray(0, size), 0, size);
        struct.read();
    }

}
