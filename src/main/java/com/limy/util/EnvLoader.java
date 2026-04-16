package com.limy.util;

import com.limy.constant.Constants;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class EnvLoader {
    public static void loadEnv() {
        Properties props = new Properties();
        try (InputStream inputStream = EnvLoader.class.getClassLoader()
                .getResourceAsStream("env.properties")) {
            if (inputStream == null) {
                IO.println("Sorry, unable to find properties file");
                return;
            }
            props.load(inputStream);
            props.forEach((key, v) -> {
                System.setProperty(key.toString(), v.toString());
            });
            if (System.getProperty(Constants.ANTHROPIC_BASE_URL_KEY) == null) {
                System.setProperty(Constants.ANTHROPIC_BASE_URL_KEY, "https://api.anthropic.com");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
