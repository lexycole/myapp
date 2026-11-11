package com.ubagroup.febaalert;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.Date;

/**
 *  @author: Olawale Akinro
 *  @version: 2.0
 */

@SpringBootApplication
@EnableScheduling
public class FebaalertApplication {

    private static final Logger LOG = LoggerFactory.getLogger(FebaalertApplication.class);

    public static void main(String[] args) {
        System.out.println("\n\n\n*********************Starting SCHEDULING FOR FEBA ALERT EMAIL NOTIFICATION from Spring_BOOT******************************* On "+new Date()+"\n\n\n");
        String banner = "  __      _           _            _   \n" +
                " / _| ___| |__   __ _| | ___ _ __ | |_ \n" +
                "| |_ / _ \\ '_ \\ / _` | |/ _ \\ '__|| __|\n" +
                "|  _|  __/ |_) | (_| | |  __/ |   | |_ \n" +
                "|_|  \\___|_.__/ \\__,_|_|\\___|_|    \\__|\n";
        LOG.info(banner);
        SpringApplication.run(FebaalertApplication.class, args);
    }

}
