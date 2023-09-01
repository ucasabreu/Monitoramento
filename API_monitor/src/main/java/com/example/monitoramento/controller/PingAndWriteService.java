package com.example.monitoramento.controller;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.time.LocalDateTime;


public class PingAndWriteService {
	private String timestamp;
	private long responseTime;
	
	public void writeDataHostParallel(HostController hc,String ip, String outputPath) {
		this.timestamp = time();
		this.responseTime = ping(ip);
		
		hc.addInfoHost(this.responseTime, this.timestamp);
		
		String timestampResponseTime = String.format("%s-%04d\n", timestamp, responseTime);
		
		try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath, true))) {
			bw.write(timestampResponseTime);
			//System.out.println(host + ":" + timestampResponseTime);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
		
	private String time() {
		return String.format("%04d-%02d-%02d-%02d-%02d-%02d",
				LocalDateTime.now().getYear(),
				LocalDateTime.now().getMonthValue(),
				LocalDateTime.now().getDayOfMonth(),
				LocalDateTime.now().getHour(),
				LocalDateTime.now().getMinute(),
				LocalDateTime.now().getSecond());
	
	}
	private long ping(String ip) {
		long responseTime = 0;
		try {
			responseTime = System.currentTimeMillis();

			if (InetAddress.getByName(ip).isReachable(2000)) {
				responseTime = System.currentTimeMillis() - responseTime;
			} else {
				responseTime = 0;
			}
		} catch (IOException e) {
			return 0;
		}
		return responseTime;
	}



}
