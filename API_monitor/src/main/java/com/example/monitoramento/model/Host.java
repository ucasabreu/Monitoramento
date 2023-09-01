package com.example.monitoramento.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public class Host {
	@JsonProperty("hostname")
	private String hostname; 
	
	@JsonProperty("ip")
	private String ip;
	
	@JsonProperty("timestamp")
	private	String timestamp;
	
	@JsonProperty("responseTime")
	private	long responseTime;
	
	@JsonProperty("nping")
	private	double nping;
	
	@JsonProperty("qtdZeroPing")
	private double qtdZeroPing;
	
	@JsonProperty("qtdPingsThisMonth")
	private double qtdPingsThisMonth;
	
	@JsonProperty("qtdZeroPingsThisMonth")
	private double qtdZeroPingsThisMonth;
	
	@JsonProperty("qtdFallEvents")
	private double qtdFallEvents;
	
	@JsonProperty("qtdFallEventsThisMonth")
	private double qtdFallEventsThisMonth;
	
	@JsonProperty("lastDownIndex")
	private String lastDownTimestamp;
	
	@JsonProperty("lastUpIndex")
	private String lastUpTimestamp;


	public Host() {}

	public Host(String name,String ip) {
		this.hostname = name;
		this.ip = ip;
		this.timestamp = null;
		this.responseTime = -1;
		this.nping = 0.0;
		this.qtdZeroPing = 0.0;
		this.qtdPingsThisMonth = 0.0;
		this.qtdZeroPingsThisMonth = 0.0;
		this.qtdFallEvents = 0.0;
		this.qtdFallEventsThisMonth = 0.0;
		this.lastDownTimestamp = null;
		this.lastUpTimestamp = null;

	}

	public String getIp() {
		return ip;
	}

	public String getHostname() {
		return hostname;
	}


	public void setHostname(String hostname) {
		this.hostname = hostname;
	}


	public String getTimestamp() {
		return timestamp;
	}


	public void setTimestamp(String timestamp) {
		this.timestamp = timestamp;
	}


	public long getResponseTime() {
		return responseTime;
	}


	public void setResponseTime(long responsetime) {
		this.responseTime = responsetime;
	}


	public double getQtdFallEvents() {
		return qtdFallEvents;
	}

	public void setQtdFallEvents(double qtdFallEvents) {
		this.qtdFallEvents = qtdFallEvents;
	}


	public double getQtdFallEventsThisMonth() {
		return qtdFallEventsThisMonth;
	}


	public void setQtdFallEventsThisMonth(double qtdFallEventsThisMonth) {
		this.qtdFallEventsThisMonth = qtdFallEventsThisMonth;
	}


	public double getNping() {
		return nping;
	}


	public void setNping(double nping) {
		this.nping = nping;
	}


	public double getQtdZeroPing() {
		return qtdZeroPing;
	}


	public void setQtdZeroPing(double qtdZeroPing) {
		this.qtdZeroPing = qtdZeroPing;
	}


	public double getQtdPingsThisMonth() {
		return qtdPingsThisMonth;
	}


	public void setQtdPingsThisMonth(double qtdPingsThisMonth) {
		this.qtdPingsThisMonth = qtdPingsThisMonth;
	}


	public double getQtdZeroPingsThisMonth() {
		return qtdZeroPingsThisMonth;
	}


	public void setQtdZeroPingsThisMonth(double qtdZeroPingsThisMonth) {
		this.qtdZeroPingsThisMonth = qtdZeroPingsThisMonth;
	}


	public String getLastDownTimestamp() {
		return lastDownTimestamp;
	}


	public void setLastDownTimestamp(String lastDownTimestamp) {
		this.lastDownTimestamp = lastDownTimestamp;
	}


	public String getLastUpTimestamp() {
		return lastUpTimestamp;
	}

	public void setLastUpTimestamp(String lastUpTimestamp) {
		this.lastUpTimestamp = lastUpTimestamp;
	}


	@Override
	public String toString() {
		return "Host{" +
				"hostname='" + hostname + '\'' +
				", timestamp='" + timestamp + '\'' +
				", responseTime=" + responseTime +
				", nping=" + nping +
				", qtdZeroPing=" + qtdZeroPing +
				", qtdPingsThisMonth=" + qtdPingsThisMonth +
				", qtdZeroPingsThisMonth=" + qtdZeroPingsThisMonth +
				", qtdFallEvents=" + qtdFallEvents +
				", qtdFallEventsThisMonth=" + qtdFallEventsThisMonth +
				", lastDownIndex=" + lastDownTimestamp +
				", lastUpIndex=" + lastUpTimestamp +
				'}';
	}

}
