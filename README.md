ProcessIsolatedTika
===================

Version: v0.0.2-SNAPSHOT

Project to abstract the use of tika-server in a seperate process/JVM

This project runs a background process/JVM for tika-server to isolate its execution.  Files/InputStreams can be passed to this class for parsing, and they are sent to the tika-server via JAXRS.  The return values are populated in to the Metadata object that is passed to the parse() method, just like a normal call to Tika.

A copy of a tika-server jar will be contained within this jar and is copied to a temporary folder for background execution.

If a call to the tika-server takes longer than 10 seconds (by default) the tika-server is restarted.

Benefits
--------

A tika-server is already running in the background and there is no JVM start cost.  Communication with tika-server is encapsulated within this class.  Speed should be close to pure-Java combined with networking overhead for JAXRS.

A crash of the tika-server should not take out the controlling JVM.

Notes
-----

* To change Tika version you just need to change it once in the pom.  However, if you are reusing this as a library and this jar's manifest is changes then you need to ensure that the version in the class matches the pom.

* Also - this library blocks until the tika-server is started (or it has waited 120s).  During this time unwanted stack traces are printed to the log at WARN level but we set JAXRS to ERROR (see log4j.properties)

* At the moment several copies of tika-server.jar may be contained in projects depending on this library depending on how they are built