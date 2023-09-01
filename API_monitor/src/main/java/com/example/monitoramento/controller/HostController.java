package com.example.monitoramento.controller;

import java.time.LocalDateTime;

import com.example.monitoramento.model.Host;


public class HostController {
	static String timenow = LocalDateTime.now().toString();
	public Host host;
	
	public HostController() {}
	public HostController (Host host) {
		this.host = host;
	}
	
	//Adiciona o responsetime e o timestamp no objeto host criado e faz todos os calculos possiveis
	
	public void addInfoHost(long responsetime,String timestamp) {
		double nping = this.host.getNping();
		double npingMonth = this.host.getQtdPingsThisMonth();
		double qtdZeroPing = this.host.getQtdZeroPing();
		double qtdZeroPingMonth = this.host.getQtdZeroPingsThisMonth();
		double fallEvent = this.host.getQtdFallEvents();
		double fallEventMonth = this.host.getQtdFallEventsThisMonth();
		
		long lastResponseTime;
			
		if(this.host.getResponseTime() >= 0.0) {
			lastResponseTime = this.host.getResponseTime();
		}else {
			lastResponseTime = responsetime;
		}  
		this.host.setResponseTime(responsetime);
		this.host.setTimestamp(timestamp);
		
		if(nping > 0.0) {
			if(this.host.getResponseTime() == 0.0) {
				this.host.setQtdZeroPing(++qtdZeroPing);
				if(lastResponseTime > 0.0) {
					this.host.setQtdFallEvents(++fallEvent);
					this.host.setLastDownTimestamp(timestamp);
				}
				if(timenow.split("-")[1].equals(timestamp.split("-")[1])) {
					this.host.setQtdZeroPingsThisMonth(++qtdZeroPingMonth);
					if(lastResponseTime > 0.0){
						this.host.setQtdFallEventsThisMonth(++fallEventMonth);
					}
				}
			}else if(lastResponseTime == 0.0) {
				this.host.setLastUpTimestamp(timestamp);
			}
		}
		if(timenow.split("-")[1].equals(timestamp.split("-")[1])) {
			this.host.setQtdPingsThisMonth(++npingMonth);
		}
		this.host.setNping(++nping);
	}
	
	
}
