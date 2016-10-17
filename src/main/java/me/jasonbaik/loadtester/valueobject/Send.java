package me.jasonbaik.loadtester.valueobject;

import java.io.Serializable;
import java.util.List;

import me.jasonbaik.loadtester.sender.AbstractSenderConfig;
import me.jasonbaik.loadtester.sender.Sender;

public class Send<T> implements Serializable {

	private static final long serialVersionUID = 1L;

	private String name;
	private AbstractSenderConfig<Sender<T>> senderConfig;
	private String clientUUID;
	private List<ReportData> reportData;

	public AbstractSenderConfig<Sender<T>> getSenderConfig() {
		return senderConfig;
	}

	public void setSenderConfig(AbstractSenderConfig<Sender<T>> senderConfig) {
		this.senderConfig = senderConfig;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public List<ReportData> getReportData() {
		return reportData;
	}

	public void setReportData(List<ReportData> reportData) {
		this.reportData = reportData;
	}

	public String getClientUUID() {
		return clientUUID;
	}

	public void setClientUUID(String clientUUID) {
		this.clientUUID = clientUUID;
	}

	@Override
	public String toString() {
		return "Send [name=" + name + ", senderConfig=" + senderConfig + ", clientUUID=" + clientUUID + ", reportData=" + reportData + "]";
	}

}