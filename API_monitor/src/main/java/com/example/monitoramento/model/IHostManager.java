package com.example.monitoramento.model;

public interface IHostManager {
	   
	    boolean createListHost(String path);
	   
	    boolean createDirHost(String path);
	    
	    boolean renameFile(String currentName, String newName);
	    
	    boolean createFile(String currentName, String path);
}
