package io.github.ngirchev.opendaimon.example;

import io.github.ngirchev.dotenv.DotEnvLoader;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class StarterConsumerApplication {

    public static void main(String[] args) {
        DotEnvLoader.loadDotEnv();
        SpringApplication.run(StarterConsumerApplication.class, args);
    }
}
