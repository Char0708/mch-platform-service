package io.github.char0708.mch.platform;

import org.springframework.boot.SpringApplication;

public class TestMchPlatformServiceApplication {

	public static void main(String[] args) {
		SpringApplication.from(MchPlatformServiceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
