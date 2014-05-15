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
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.log4j.Logger;
import org.apache.tika.io.CloseShieldInputStream;
import org.apache.tika.io.IOUtils;
import org.apache.tika.metadata.Metadata;

import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.Status;

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
	private File gLocalJar = null;
	private ToolRunner gRunner;
	private boolean gRunning = false;
	private Response gResponse;

	private int TIKA_SERVER_PORT = 9998;
	private final String TIKA_LOCAL_HOST = "127.0.0.1";

	///////////////////////////////////////////////////////////
	// Constructor
	///////////////////////////////////////////////////////////

	/**
	 * Create and start a new tika-server instance
	 */
	public ProcessIsolatedTika() {
		init();
		// Prefer IPv4 over IPv6
		System.setProperty("java.net.preferIPv4Stack" , "true");
		start();
	}
	
	///////////////////////////////////////////////////////////
	// Methods
	///////////////////////////////////////////////////////////

	/**
	 * Extract the tika-server jar and prepare for execution
	 */
	private void init() {
		
		if(gLocalJar!=null) return;
		
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
		boolean found = false;
		InputStream jar = ProcessIsolatedTika.class.getClassLoader().getResourceAsStream(TIKA_JAR);
		if(jar!=null) {
			gLogger.info("Found server jar: /");
			//Tools.copyStreamToFile(jar, gLocalJar);
			found = true;
		} 
		if(!found) {
			jar = ProcessIsolatedTika.class.getClassLoader().getResourceAsStream("lib/"+TIKA_JAR);
			if(jar!=null) {
				gLogger.info("Found server jar: lib/");
				//Tools.copyStreamToFile(jar, gLocalJar);
				found = true;
			} 
		}
		if(!found) {	
			final String JARFILE = "target/resources/"+TIKA_JAR;
			try {
				jar = new FileInputStream(JARFILE);
				gLogger.info("Found jar in filesystem");
			} catch (FileNotFoundException e) {
				// TODO Auto-generated catch block
				gLogger.info("Cannot find jar");
				e.printStackTrace();
			}
			//IOUtils.copy(new FileInputStream(new File(JARFILE)), new FileOutputStream(gLocalJar));
			found = true;
		}
		
		try {
			FileOutputStream fos = new FileOutputStream(gLocalJar); 
			IOUtils.copy(jar, fos);
			fos.close();
			jar.close();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		gLocalJar.deleteOnExit();
		
		gLogger.info("Temp jar: "+gLocalJar.getAbsolutePath()+", size: "+gLocalJar.length());
		
	}
	
	/**
	 * Start the server
	 */
	private void start(int pPort) {

		ArrayList<String> commandLine = new ArrayList<String>();
		for(String s: new String[] { "java", "-Djava.net.preferIPv4Stack=true", "-jar", gLocalJar.getAbsolutePath(), "-p", Integer.toString(pPort) }) {
			commandLine.add(s);
		}
		
		gLogger.info("Starting: "+commandLine);
		
		gRunner = new ToolRunner();
		try {
			gRunner.start(commandLine);
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		
		// Allow time for Tika to get started otherwise we get into a mess
		blockUntilStarted();
		
		gRunning = true;
		
	}
		
	// keep checking until we receive the version number from Tika
	private void blockUntilStarted() {
		final String TIKA_PATH = "/version";
		final String END_POINT = "http://"+TIKA_LOCAL_HOST+":"+TIKA_SERVER_PORT;
		
		gLogger.trace("Waiting for tika-server to initialise ("+END_POINT+TIKA_PATH+")");

		boolean ready = false;
		final long startTime = System.currentTimeMillis();
		final int timeStepMS = 100;
		
		while(!ready) {
			try {
				Response response = WebClient.create(END_POINT+TIKA_PATH)
						.type(MediaType.TEXT_PLAIN)
						.accept(MediaType.TEXT_PLAIN)
						.get();
				
				if(response.getStatus()==Response.Status.OK.getStatusCode()) {
					// if we got a good response then we should be up and running
					ready = true;
				}
			} catch (Exception e) {
				
			} 
			try {
				Thread.sleep(timeStepMS);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				//e.printStackTrace();
			}
		
			if((System.currentTimeMillis()-startTime)>120*1000) {
				gLogger.error("tika-server failed to start");
				break;
			}
			
		}

		// One more second, for good luck
		try {
			Thread.sleep(timeStepMS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			//e.printStackTrace();
		}
		
		gLogger.info("Server up and running in "+(System.currentTimeMillis()-startTime)+"ms");
		
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
		
		gLogger.info("Stopping...");
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
	public boolean parse(final InputStream pInputStream, final Metadata pMetadata) {

		boolean ret = true;
		
		if(!gRunner.isRunning()) {
			gLogger.error("Tika-Server is not running");
			return false;
		}
		
		final String TIKA_PATH = "/meta";
		final String END_POINT = "http://"+TIKA_LOCAL_HOST+":"+TIKA_SERVER_PORT;
		
		gLogger.trace("Server: "+END_POINT+TIKA_PATH);
		
		final String detectedType = pMetadata.get(Metadata.CONTENT_TYPE); 
		
		FutureTask<Integer> task = new FutureTask<Integer>(new Callable<Integer>() {
			@Override
			public Integer call() throws Exception {

				gResponse = WebClient.create(END_POINT+TIKA_PATH)
						.accept("text/csv")
						// give the parsers a hint
						.type(detectedType)
						// protect the stream from being closed
						.put(new CloseShieldInputStream(pInputStream));

				return null;
			}});
		
		Thread thread = new Thread(task);
		thread.start();
		
		try {
			task.get(TIMEOUT_SECS*1000, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			gLogger.info("InterruptedException: "+e);
			ret = false;
			restart();
		} catch (ExecutionException e) {
			gLogger.info("ExecutionException: "+e);
			ret = false;
			restart();
		} catch (TimeoutException e) {
			gLogger.info("TimeoutException: "+e);
			ret = false;
			restart();
		} 
		
		if(gResponse!=null) {
			if(gResponse.getStatus()==Status.UNSUPPORTED_MEDIA_TYPE.getStatusCode()) {
				// the server may return HTTP 415 (unsupported) if it won't accept the mimetype
				// handle this issue here
				// add some text to the output
				// FIXME: maybe change mimetype for a more visible error?
				pMetadata.add("parseFailure415", "true");
				gLogger.error("Parse Failure: HTTP 415 (format unsupported for parsing)");
			} else {
				if(gResponse.getEntity() instanceof InputStream) {
					InputStream is = (InputStream)gResponse.getEntity();
					BufferedReader reader = new BufferedReader(new InputStreamReader(is));
					try {
						Iterable<CSVRecord> records = CSVFormat.DEFAULT.parse(reader);
						for(CSVRecord record:records) {
							pMetadata.add(record.get(0), record.get(1));
						}
					} catch (IOException e1) {
						// TODO Auto-generated catch block
						e1.printStackTrace();
						ret = false;
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
		} 

		gLogger.info("Metadata entries: "+pMetadata.names().length);
				
		return ret;
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

		gLogger.info("Processing: "+pFile);

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
		try {
			System.out.println("Press any key to exit");
			System.in.read();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		pit.stop();
	}
	
}

