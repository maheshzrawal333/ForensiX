package com.maheshz.ForensiX.engine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Enterprise Application Bootstrap and IoC Container Root.
 * <p>
 * This class serves as the primary JVM entry point for the ForensiX RAG Engine.
 * It orchestrates the initialization of the entire backend architecture, including:
 * <ul>
 * <li>The embedded Tomcat web server and strictly secured HTTP perimeters.</li>
 * <li>The HikariCP database connection pools (PostgreSQL/pgvector).</li>
 * <li>The Asynchronous Task Executor pool for distributed S3 ingestion.</li>
 * <li>The Spring AI ChatClient and local Ollama socket bindings.</li>
 * </ul>
 * <p>
 * ARCHITECTURAL BOUNDARY:
 * Because this class is located at the root package {@code com.maheshz.ForensiX.engine},
 * Spring's implicit component scanner will automatically traverse and load all sub-packages
 * (controller, service, repository, security). Any components placed completely outside
 * of this package tree will be deliberately ignored by the application context.
 */
@SpringBootApplication
public class ForensiXEngineApplication {

	/**
	 * The Main Thread execution hook.
	 * <p>
	 * This method transitions control from the standard Java Virtual Machine (JVM)
	 * to the Spring ApplicationContext lifecycle.
	 *
	 * @param args Command-line arguments passed during deployment (e.g., overriding
	 * active Spring Profiles, AWS credentials, or overriding the default
	 * Ollama port in a production Docker container).
	 */
	public static void main(String[] args) {

		// Bootstraps the application, performs classpath scanning, applies auto-configurations
		// (like Redis PubSub and Spring Data JPA), and binds the application to the network port.
		SpringApplication.run(ForensiXEngineApplication.class, args);

	}
}