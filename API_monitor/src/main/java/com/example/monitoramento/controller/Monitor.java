package com.example.monitoramento.controller;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import com.example.monitoramento.model.Host;
import com.example.monitoramento.model.IHostManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;


public class Monitor implements ApplicationRunner,IHostManager {
	private PingAndWriteService pingAndWriteService = new PingAndWriteService();
	private Map<String,HostController> listHosts = new HashMap<>();
	private volatile boolean running = true;

	private static final String  OUTPUTDIRECTORY = "../API_monitor/src/main/resources/monitor-resources-novo/hostpings/HOSTS/";
	private static final String PATH = "../API_monitor/src/main/resources/monitor-resources-novo/hostpings/";
	private static final int MAX_ITERATIONS = 10000;
	
	private File file = new File(PATH+"HOSTS");
	private File[] arquivos = file.listFiles();

	@Override
	public void run(ApplicationArguments args) throws Exception {
		startPingHosts();
	}

	public void startPingHosts() {
		ExecutorService executor = Executors.newCachedThreadPool();

		loadHostControllersFromDocs();

		int iterations = 0;

		while (running && iterations < MAX_ITERATIONS) {

			for(String key: listHosts.keySet()) {
				String host = listHosts.get(key).host.getHostname();
				String ip = listHosts.get(key).host.getIp(); 
				String outputPath = OUTPUTDIRECTORY + host + ".txt";
				
				// Cria uma tarefa assíncrona para cada host
				executor.execute(() -> {
					try {
						pingAndWriteService.writeDataHostParallel(listHosts.get(host),ip, outputPath);
					} catch (Exception e) {
						e.printStackTrace();
					}
				});
			}
			try {
				TimeUnit.SECONDS.sleep(10);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			updateHostDataJSON();
			iterations++;
		}

		executor.shutdown();
	}


	private void loadHostControllersFromDocs() {
		ExecutorService executor = Executors.newCachedThreadPool();
		try {
			createListHost(PATH+"hostgroup.txt");
			if(!this.listHosts.isEmpty()) {
				
				for(File fileHost: arquivos) {
					List<String> lines = Files.readAllLines(fileHost.toPath());
					executor.execute(() -> {
						try {	
							for(String linha: lines) {
								if(lines != null) {
									long responsetime = Long.parseLong(linha.split("-")[6]);
									String timestamp = linha.substring(0, 19);
									String key = fileHost.getName().replace(".txt","");
									listHosts.get(key).addInfoHost(responsetime, timestamp);
								}	
							}			
						}catch(Exception e) {
							e.printStackTrace();
						}
					});
				}
			}
		}catch (Exception e) {
			e.printStackTrace();
		}	
		executor.shutdown();
	}

	public boolean createListHost(String path) {
		try(BufferedReader br = new BufferedReader(new FileReader(path))) {
			String line;
			while ((line = br.readLine()) != null) {
				String host = line.split("#")[0];
				String ip = line.split("#")[1];
				listHosts.put(host, new HostController(new Host(host,ip)));
			}
		}catch(Exception e) {
			e.printStackTrace();
			return false;
		}
		return true;
	}
	
	@Override
	//funcao para criar os arquivos da lista hostgroup.txt
	public boolean createDirHost(String path) {
		List<String> hosts = new ArrayList<>();
		try {
			File folder =  new File(PATH+"HOSTS");
			if(!folder.exists()) {
				folder.mkdirs();
			}
			hosts = Files.readAllLines(Paths.get(path));
			for(String host:hosts) {
				
				String filename = host.split("#")[0] + ".txt";
				File file = new File(folder,filename);
				file.createNewFile();
			}
		}catch (IOException e) {
            e.printStackTrace();
        }
		
		
		return true;
	}
	
	@Override
	//funcao para renomear os arquivos do diretorio HOST
	public boolean renameFile(String currentName, String newName) {
	        File file = new File(currentName);
	        
	        if (file.exists()) {
	            File newFile = new File(newName);
	            
	            if (file.renameTo(newFile)) {
	               return true;
	            }
	        } 
	        return false;
	 }
	
	@Override
	 //funcao para criar um arquivo
	public boolean createFile(String currentName, String path) {
		File folder = new File(path);
		if(!folder.exists()) {
			return false;
		}
		try {
			File file = new File(folder,currentName);
			if(!file.createNewFile()) {
				return false;
			}
		}catch(IOException e) {
			e.printStackTrace();
		}
		
		return true;
	}
	 
	
	private void updateHostDataJSON() {
		File analyzer = new File("../API_monitor/src/main/resources/hostdata.json");
		try {
			analyzer.createNewFile();
			ObjectMapper mapper = new ObjectMapper();
			mapper.enable(SerializationFeature.INDENT_OUTPUT);
			/*List<Host> hosts = new ArrayList<>();
			
			for(HostController h:this.listHosts.values() ) {
				hosts.add(h.host);
			}*/
			
		    mapper.writeValue(analyzer, this.listHosts.values());

		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	// Método para interromper a execução do loop
	public void stopPingHosts() {
		running = false;
	}
}
