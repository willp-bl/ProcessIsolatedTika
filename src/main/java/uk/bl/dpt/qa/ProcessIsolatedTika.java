/*
 * Copyright 2014 The British Library/SCAPE Project Consortium
 * Author: William Palmer (William.Palmer@bl.uk)
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package uk.bl.dpt.qa;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.Logger;
import org.apache.tika.io.IOUtils;
import org.apache.tika.metadata.Metadata;

import javax.ws.rs.core.Response;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.apache.cxf.jaxrs.client.WebClient;

/**
 * Run a tika-server instance and use it to parse files
 * This method has been chosen as Tika parsers can potentially stop or crash and we don't 
 * want this to take out the whole JVM
 * @author wpalmer
 */
public class ProcessIsolatedTika {

	private Logger gLogger = Logger.getLogger(ProcessIsolatedTika.class);

	///////////////////////////////////////////////////////////
	// Constants
	///////////////////////////////////////////////////////////

	private static final int TIMEOUT_SECS = 10;
	private static final String TIKA_VER_KEY = "tika-version";// must match pom
	private static final String MANIFEST = "META-INF/MANIFEST.MF";
	
	///////////////////////////////////////////////////////////
	// Global variables
	///////////////////////////////////////////////////////////

	// Initialise in case of running from Eclipse
	private String TIKA_VERSION = "1.5";
	private String TIKA_JAR;
	private File gLocalJar;
	private ToolRunner gRunner;
	private boolean gRunning = false;
	private Response gResponse;

	private int TIKA_SERVER_PORT = 9998;

	///////////////////////////////////////////////////////////
	// Constructor
	///////////////////////////////////////////////////////////

	/**
	 * Create and start a new tika-server instance
	 */
	public ProcessIsolatedTika() {
		init();
		start();
	}
	
	///////////////////////////////////////////////////////////
	// Methods
	///////////////////////////////////////////////////////////

	/**
	 * Extract the tika-server jar and prepare for execution
	 */
	private void init() {
		
		// get tika version from manifest file
		InputStream manifest = ProcessIsolatedTika.class.getClassLoader().getResourceAsStream(MANIFEST);
		if(manifest!=null) {
			BufferedReader reader = new BufferedReader(new InputStreamReader(manifest));
			String line = "";
			try {
				while(reader.ready()) {
					line = reader.readLine();
					if(line.startsWith(TIKA_VER_KEY)) {
						TIKA_VERSION = line.substring(TIKA_VER_KEY.length()+2).trim();
						break;
					}
				}
			} catch (IOException e) {
				e.printStackTrace();
			} finally {
				if(reader!=null) {
					try {
						reader.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
				if(manifest!=null) {
					try {
						manifest.close();
					} catch (IOException e) {
						// TODO Auto-generated catch block
						e.printStackTrace();
					}
				}
			}
		}
		
		TIKA_JAR = "tika-server-"+TIKA_VERSION+".jar";
		
		// copy jar from resources folder to temporary space
		try {
			gLocalJar = File.createTempFile("tika-server-", ".jar");
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		boolean copied = false;
		InputStream jar = ProcessIsolatedTika.class.getClassLoader().getResourceAsStream(TIKA_JAR);
		if(jar!=null) {
			gLogger.trace("Found server jar: /");
			//Tools.copyStreamToFile(jar, gLocalJar);
			copied = true;
		} 
		if(!copied) {
			jar = ProcessIsolatedTika.class.getClassLoader().getResourceAsStream("lib/"+TIKA_JAR);
			if(jar!=null) {
				gLogger.trace("Found server jar: lib/");
				//Tools.copyStreamToFile(jar, gLocalJar);
				copied = true;
			} 
		}
		if(!copied) {	
			final String JARFILE = "target/resources/"+TIKA_JAR;
			try {
				jar = new FileInputStream(JARFILE);
				gLogger.trace("Found jar in filesystem");
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				gLogger.trace("Cannot find jar");
				e.printStackTrace();
			}
			//IOUtils.copy(new FileInputStream(new File(JARFILE)), new FileOutputStream(gLocalJar));
			copied = true;
		}
		
		try {
			IOUtils.copy(jar, new FileOutputStream(gLocalJar));
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		gLocalJar.deleteOnExit();
		
		gLogger.trace("Temp jar: "+gLocalJar.getAbsolutePath()+", size: "+gLocalJar.length());
		
	}
	
	/**
	 * Start the server
	 */
	private void start(int pPort) {

		ArrayList<String> commandLine = new ArrayList<String>();
		for(String s: new String[] { "java", "-jar", gLocalJar.getAbsolutePath(), "-p", Integer.toString(pPort) }) {
			commandLine.add(s);
		}
		
		gLogger.trace("Starting: "+commandLine);
		
		gRunner = new ToolRunner();
		try {
			gRunner.start(commandLine);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		gRunning = true;
		
	}
	
	private void start() {

		if(gRunning) return;
		
		TIKA_SERVER_PORT = Tools.getFreePort(TIKA_SERVER_PORT);

		start(TIKA_SERVER_PORT);

	}
	
	/**
	 * Stop the tika-server
	 */
	public void stop() {
		
		if(!gRunning) return;
		
		gLogger.trace("Stopping...");
		gRunner.stop();
		
		gRunning = false;
		
	}
	
	/**
	 * Restart the Tika server
	 */
	private void restart() {
		stop();
		start();
	}
	
	/**
	 * Parse an inputstream and populate a Metadata object
	 * @param pInputStream stream to analyse 
	 * @param pMetadata metadata object to populate
	 * @param pOutputStream output to write data to
	 * @return true if processed ok, false if execution was terminated
	 */
	public boolean parse(final InputStream pInputStream, Metadata pMetadata) {

		if(!gRunner.isRunning()) {
			gLogger.error("Tika-Server is not running");
			return false;
		}
		
		final String TIKA_PATH = "/meta";
		final String END_POINT = "http://localhost:"+TIKA_SERVER_PORT;
		
		FutureTask<Integer> task = new FutureTask<Integer>(new Callable<Integer>() {
			@Override
			public Integer call() throws Exception {

				gResponse = WebClient.create(END_POINT+TIKA_PATH)
						.accept("text/csv")
						.put(pInputStream);

				return null;
			}});
		
		Thread thread = new Thread(task);
		thread.start();
		
		try {
			task.get(TIMEOUT_SECS*1000, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			gLogger.trace("InterruptedException: "+e);
			restart();
		} catch (ExecutionException e) {
			gLogger.trace("ExecutionException: "+e);
			restart();
		} catch (TimeoutException e) {
			gLogger.trace("TimeoutException: "+e);
			restart();
		}
		
		if(gResponse!=null) {
			if(gResponse.getEntity() instanceof InputStream) {
				BufferedReader reader = new BufferedReader(new InputStreamReader((InputStream)gResponse.getEntity()));
				try {
					Iterable<CSVRecord> records = CSVFormat.DEFAULT.parse(reader);
					for(CSVRecord record:records) {
						pMetadata.add(record.get(0), record.get(1));
					}
				} catch (IOException e1) {
					// TODO Auto-generated catch block
					e1.printStackTrace();
				} finally {
					if(reader!=null) {
						try {
							reader.close();
						} catch (IOException e) {
							// TODO Auto-generated catch block
							e.printStackTrace();
						}
					}
				}
			}
		}

		gLogger.trace("Metadata entries: "+pMetadata.names().length);
				
		return false;
	}
	
	/**
	 * Parse a file and populate a Metadata object
	 * @param pFile file to analyse
	 * @param pMetadata metadata object to populate
	 * @param pOutputStream output to write data to
	 * @return true if processed ok, false if execution was terminated
	 */
	public boolean parse(final File pFile, Metadata pMetadata) {

		if(!pFile.exists()) return false;

		gLogger.trace("Processing: "+pFile);

		if(pMetadata.get(Metadata.RESOURCE_NAME_KEY)!=null) {
			pMetadata.set(Metadata.RESOURCE_NAME_KEY, pFile.getAbsolutePath());
		}
		
		try {
			return parse(new FileInputStream(pFile), pMetadata);
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		return false;
	}
	
	/**
	 * Test main method
	 * @param args
	 */
	public static void main(String[] args) {
		ProcessIsolatedTika pit = new ProcessIsolatedTika();
		pit.parse(new File("doc.doc"), new Metadata());
		pit.parse(new File("corrupt.mp3"), new Metadata());
		pit.parse(new File("v1.pdf"), new Metadata());
		pit.stop();
	}
	
}

