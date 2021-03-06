package me.jasonbaik.loadtester.valueobject;

import java.io.Serializable;
import java.util.List;

public class Scenario<S1, R1> implements Serializable {

	private static final long serialVersionUID = 1L;

	private String name;
	private List<Broker> brokers;
	private List<Send<S1>> sends;
	private List<Receive<R1>> receives;
	private long maxAttackTimeSeconds;
	private String reportDir;
	private boolean gcBrokers = true;

	public List<Send<S1>> getSends() {
		return sends;
	}

	public void setSends(List<Send<S1>> sends) {
		this.sends = sends;
	}

	public long getMaxAttackTimeSeconds() {
		return maxAttackTimeSeconds;
	}

	public void setMaxAttackTimeSeconds(long maxAttackTimeSeconds) {
		this.maxAttackTimeSeconds = maxAttackTimeSeconds;
	}

	public List<Receive<R1>> getReceives() {
		return receives;
	}

	public void setReceives(List<Receive<R1>> receives) {
		this.receives = receives;
	}

	public String getReportDir() {
		return reportDir;
	}

	public void setReportDir(String reportDir) {
		this.reportDir = reportDir;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<Broker> getBrokers() {
		return brokers;
	}

	public void setBrokers(List<Broker> brokers) {
		this.brokers = brokers;
	}

	public boolean isGcBrokers() {
		return gcBrokers;
	}

	public void setGcBrokers(boolean gcBrokers) {
		this.gcBrokers = gcBrokers;
	}

	@Override
	public String toString() {
		return "Scenario [name=" + name + ", brokers=" + brokers + ", sends=" + sends + ", receives=" + receives + ", maxAttackTimeSeconds=" + maxAttackTimeSeconds + ", reportDir=" + reportDir
				+ ", gcBrokers=" + gcBrokers + "]";
	}

}