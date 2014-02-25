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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.apache.log4j.Logger;

/**
 * This class runs an external tool via command line until it exits or stop() is called
 * This "drainer" Thread approach has to be used as otherwise Windows hangs with JDK6 
 * @author wpalmer
 */
public class ToolRunner {

	private Logger gLogger = Logger.getLogger(ToolRunner.class);

	private Process gProcess;
	private Thread gDrainer;
	
	private ByteArrayOutputStream byteArrayStdout;
	private InputStream stdout;

	/**
	 * Create a new ToolRunner (not redirecting stderr to stdout)
	 */
	public ToolRunner() {
		// TODO Auto-generated constructor stub
	}
	
	/**
	 * Executes a given command line.  
	 * @param pCommandLine command line to run
	 * @throws IOException error
	 */
	public void start(List<String> pCommandLine) throws IOException {
		//check there are no command line options that are empty
		while(pCommandLine.contains("")) {
			pCommandLine.remove("");
		}

		ArrayList<String> commandLine = new ArrayList<String>();
		commandLine.addAll(pCommandLine);

		ProcessBuilder pb = new ProcessBuilder(commandLine);
		
		pb.redirectErrorStream(true);
		
/* JDK7+ only
		//log outputs to file(s) - fixes hangs on windows
		//and logs *all* output (unlike when using IOStreamThread)
		File stdoutFile = File.createTempFile("stdout-log-", ".log");
		stdoutFile.deleteOnExit();
		File stderrFile = File.createTempFile("stderr-log-", ".log");
		stderrFile.deleteOnExit();
	
		pb.redirectOutput(stdoutFile);
		if(!gRedirectStderr) {
			pb.redirectError(stderrFile);
		}
 */

		//start the executable
		gProcess = pb.start();
		//create a log of the console output
		stdout = gProcess.getInputStream();
		byteArrayStdout = new ByteArrayOutputStream();
		
		// consume buffers
		// use the fact that exitvalue will throw an exception if the process is still running to drain the buffer
		// do this in a background thread
		gDrainer = new Thread(new Runnable() {
			@Override
			public void run() {
				while(true) {
					try {
						gProcess.exitValue();
						break;
					} catch(IllegalThreadStateException e) {
						try{
							byteArrayStdout.write(stdout.read());
						} catch(IOException e2) {
							e2.printStackTrace();// 
						}
					}
				}
			} });
		
		gDrainer.start();
		
		gLogger.trace("Process started successfully");
		
	}
	
	/**
	 * Is this process still running?
	 * @return true if running, false if not
	 */
	public boolean isRunning() {
		try {
			gProcess.exitValue();
			return false;
		} catch(IllegalThreadStateException e) {
			return true;
		}
	}
	
	/**
	 * Stop the process
	 */
	public void stop() {
		gProcess.destroy();
		gLogger.trace("Process stopped");
	}

}
