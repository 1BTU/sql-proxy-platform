package com.example.dmdatabaseapi.util;

import oracle.sql.CharacterSet;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class OracleCharsetTest {
    @Test
    void zhs16gbkSupportsChineseRoundTripWithoutAnOracleServer() throws Exception {
        CharacterSet charset = CharacterSet.make(CharacterSet.ZHS16GBK_CHARSET);
        String value = "中文数据库代理测试ABC123";
        byte[] bytes = charset.convert(value);
        assertArrayEquals(value.getBytes(Charset.forName("GBK")), bytes);
        assertEquals(value, charset.toString(bytes, 0, bytes.length));
    }
}
