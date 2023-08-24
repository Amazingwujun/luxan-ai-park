package com.lewin.luxanaipark.utils;

import com.lewin.commons.entity.Tuple2;
import com.lewin.commons.entity.Tuples;
import com.lewin.commons.utils.ByteUtils;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.StringReader;
import java.util.Arrays;

/**
 * 一些工具方法
 *
 * @author Jun
 * @since 1.0.0
 */
@Slf4j
public class CommonUtils {

    public static String bytes2Str(byte[] bytes) {
        return new String(bytes).split("\0", 2)[0];
    }

    public static String trimSerialNumber(String sn) {
        if (sn.length() > 11) {
            return sn.substring(sn.length() - 9);
        }
        return sn;
    }

    public static String trimSerialNumber(byte[] sn) {
        String s = bytes2Str(sn);
        return trimSerialNumber(s);
    }

    public static Tuple2<String, byte[]> parseEvent4993Payload(byte[] content) {
        // 截取
        content = Arrays.copyOfRange(content, 0, content.length - 19);
        if (content[content.length - 2] == 13 && content[content.length - 1] == 10) {
            content = Arrays.copyOfRange(content, 0, content.length - 2);
        }

        // json 读取
        String json = new String(content);
        var br = new BufferedReader(new StringReader(json));

        var startFlag = false;
        var sb = new StringBuilder();
        var lineList = br.lines().toList();
        for (var l : lineList) {
            if (l.equals("{")) {
                sb.append(l);
                startFlag = true;
                continue;
            }

            if (startFlag) {
                if (l.equals("}")) {
                    sb.append(l);
                    break;
                }
                sb.append(l.replaceAll("\t", ""));
            }
        }

        // img 图片字节数组
        var startIdx = 0;
        var endIdx = 0;
        for (int i = 0; i < content.length; i++) {
            if (i == content.length - 2) {
                break;
            }
            var item = content[i];
            if (item == '\r' && content[i + 1] == '\n' &&
                    content[i + 2] == '\r' && content[i + 3] == '\n' && startIdx < 2) {
                startIdx++;
                if (startIdx == 2) {
                    startIdx = i + 4;
                    break;
                }
            }
        }

        byte[] imgBytes;
        try {
            imgBytes = Arrays.copyOfRange(content, startIdx, content.length - 1);
        } catch (Exception e) {
            log.error("图片数据解析失败: [{}]", ByteUtils.toHexString(content));
            imgBytes = null;
        }
        return Tuples.of(sb.toString(), imgBytes);
    }
}
