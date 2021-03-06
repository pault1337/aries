/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIESOR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.aries.tx.control.itests;

import static org.ops4j.pax.exam.CoreOptions.junitBundles;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.options;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;
import static org.ops4j.pax.exam.CoreOptions.when;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Properties;

import javax.inject.Inject;

import org.h2.tools.Server;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Filter;
import org.osgi.framework.FrameworkUtil;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceReference;
import org.osgi.service.cm.ConfigurationAdmin;
import org.osgi.service.jdbc.DataSourceFactory;
import org.osgi.service.transaction.control.TransactionControl;
import org.osgi.service.transaction.control.jdbc.JDBCConnectionProvider;
import org.osgi.service.transaction.control.jdbc.JDBCConnectionProviderFactory;
import org.osgi.util.tracker.ServiceTracker;

@RunWith(PaxExam.class)
@ExamReactorStrategy(PerClass.class)
public abstract class AbstractTransactionTest {
	
	private static final String TX_CONTROL_FILTER = "org.apache.aries.tx.control.itests.filter";
	private static final String REMOTE_DB_PROPERTY = "org.apache.aries.tx.control.itests.remotedb";
	private static final String CONFIGURED_PROVIDER_PROPERTY = "org.apache.aries.tx.control.itests.configured";

	@Inject
	BundleContext context;
	
	protected TransactionControl txControl;

	protected Connection connection;

	private Server server;
	
	protected final List<ServiceTracker<?,?>> trackers = new ArrayList<>();

	@Before
	public void setUp() throws Exception {
		
		txControl = getService(TransactionControl.class, 
				System.getProperty(TX_CONTROL_FILTER), 5000);
		
		Properties jdbc = new Properties();
		
		boolean external = System.getProperties().containsKey(REMOTE_DB_PROPERTY);
		
		String jdbcUrl;
		if(external) {
			server = Server.createTcpServer("-tcpPort", "0");
			server.start();
			
			jdbcUrl = "jdbc:h2:tcp://127.0.0.1:" + server.getPort() + "/" + System.getProperty(REMOTE_DB_PROPERTY);
		} else {
			jdbcUrl = "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1";
		}
		
		jdbc.setProperty(DataSourceFactory.JDBC_URL, jdbcUrl);
		
		boolean configuredProvider = isConfigured();
		
		connection = configuredProvider ? configuredConnection(jdbc) : programaticConnection(jdbc);
		
		txControl.required(() -> {
				Statement s = connection.createStatement();
				try {
					s.execute("DROP TABLE TEST_TABLE");
				} catch (SQLException sqle) {}
				s.execute("CREATE TABLE TEST_TABLE ( message varchar(255) )");
				return null;
			});
	}

	protected Map<String, Object> resourceProviderConfig() {
		// No extra information by default
		return new HashMap<>();
	}

	public boolean isConfigured() {
		return System.getProperties().containsKey(CONFIGURED_PROVIDER_PROPERTY);
	}

	protected <T> T getService(Class<T> clazz, long timeout) {
		try {
			return getService(clazz, null, timeout);
		} catch (InvalidSyntaxException e) {
			throw new IllegalArgumentException(e);
		}
	}

	private <T> T getService(Class<T> clazz, String filter, long timeout) throws InvalidSyntaxException {
		Filter f = FrameworkUtil.createFilter(filter == null ? "(|(foo=bar)(!(foo=bar)))" : filter); 
		
		ServiceTracker<T, T> tracker = new ServiceTracker<T, T>(context, clazz, null) {
			@Override
			public T addingService(ServiceReference<T> reference) {
				return f.match(reference) ? super.addingService(reference) : null;
			}
		};

		tracker.open();
		try {
			T t = tracker.waitForService(timeout);
			if(t == null) {
				throw new NoSuchElementException(clazz.getName());
			}
			return t;
		} catch (InterruptedException e) {
			throw new RuntimeException("Error waiting for service " + clazz.getName(), e);
		} finally {
			trackers.add(tracker);
		}
	}
	
	private Connection programaticConnection(Properties jdbc) {
		
		JDBCConnectionProviderFactory resourceProviderFactory = getService(JDBCConnectionProviderFactory.class, 5000);
		
		DataSourceFactory dsf = getService(DataSourceFactory.class, 5000);
		
		return resourceProviderFactory.getProviderFor(dsf, jdbc, resourceProviderConfig())
				.getResource(txControl);
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Connection configuredConnection(Properties jdbc) throws IOException {
		
		String type = System.getProperty(CONFIGURED_PROVIDER_PROPERTY);
		
		jdbc.setProperty(DataSourceFactory.OSGI_JDBC_DRIVER_CLASS, "org.h2.Driver");
		ConfigurationAdmin cm = getService(ConfigurationAdmin.class, 5000);
		
		String pid = "local".equals(type) ? "org.apache.aries.tx.control.jdbc.local" 
				: "org.apache.aries.tx.control.jdbc.xa";
		
		System.out.println("Configuring connection provider with pid " + pid);
		
		resourceProviderConfig().entrySet().stream()
			.forEach(e -> jdbc.put(e.getKey(), e.getValue()));
		
		org.osgi.service.cm.Configuration config = cm.createFactoryConfiguration(
				pid, "?");
		config.update((Hashtable)jdbc);
		
		return getService(JDBCConnectionProvider.class, 5000).getResource(txControl);
	}
	
	@After
	public void tearDown() {

		if(isConfigured()) {
			clearConfiguration();
		}
		
		if(server != null) {
			server.stop();
		}
		
		trackers.stream().forEach(ServiceTracker::close);

		connection = null;
	}

	private void clearConfiguration() {
		ConfigurationAdmin cm = getService(ConfigurationAdmin.class, 5000);
		org.osgi.service.cm.Configuration[] cfgs = null;
		try {
			cfgs = cm.listConfigurations(null);
		} catch (Exception e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
		
		if(cfgs != null) {
			for(org.osgi.service.cm.Configuration cfg : cfgs) {
				try {
					cfg.delete();
				} catch (Exception e) {}
			}
			try {
				Thread.sleep(250);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}
	}

	@Configuration
	public Option[] localEmbeddedH2LocalTxConfiguration() {
		String localRepo = System.getProperty("maven.repo.local");
		if (localRepo == null) {
			localRepo = System.getProperty("org.ops4j.pax.url.mvn.localRepository");
		}
		
		Option testSpecificOptions = testSpecificOptions();
		
		return options(junitBundles(), systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value("INFO"),
				when(localRepo != null)
						.useOptions(CoreOptions.vmOption("-Dorg.ops4j.pax.url.mvn.localRepository=" + localRepo)),
				localTxControlService(),
				localJdbcResourceProviderWithH2(),
				when(testSpecificOptions != null).useOptions(testSpecificOptions),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-api").versionAsInProject(),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-service").versionAsInProject()
				
//				,CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
		);
	}

	@Configuration
	public Option[] localServerH2LocalTxConfiguration() {
		String localRepo = System.getProperty("maven.repo.local");
		if (localRepo == null) {
			localRepo = System.getProperty("org.ops4j.pax.url.mvn.localRepository");
		}
		
		Option testSpecificOptions = testSpecificOptions();
		
		return options(junitBundles(), systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value("INFO"),
				when(localRepo != null)
				.useOptions(CoreOptions.vmOption("-Dorg.ops4j.pax.url.mvn.localRepository=" + localRepo)),
				localTxControlService(),
				localJdbcResourceProviderWithH2(),
				systemProperty(REMOTE_DB_PROPERTY).value(getRemoteDBPath()),
				when(testSpecificOptions != null).useOptions(testSpecificOptions),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-api").versionAsInProject(),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-service").versionAsInProject()
				
//				,CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
				);
	}

	@Configuration
	public Option[] localConfigAdminDrivenH2LocalTxConfiguration() {
		String localRepo = System.getProperty("maven.repo.local");
		if (localRepo == null) {
			localRepo = System.getProperty("org.ops4j.pax.url.mvn.localRepository");
		}
		
		Option testSpecificOptions = testSpecificOptions();
		
		return options(junitBundles(), systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value("INFO"),
				when(localRepo != null)
				.useOptions(CoreOptions.vmOption("-Dorg.ops4j.pax.url.mvn.localRepository=" + localRepo)),
				localTxControlService(),
				localJdbcResourceProviderWithH2(),
				systemProperty(REMOTE_DB_PROPERTY).value(getRemoteDBPath()),
				mavenBundle("org.apache.felix", "org.apache.felix.configadmin").versionAsInProject(),
				systemProperty(CONFIGURED_PROVIDER_PROPERTY).value("local"),
				when(testSpecificOptions != null).useOptions(testSpecificOptions),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-api").versionAsInProject(),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-service").versionAsInProject()
				
//				,CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
				);
	}
	
	@Configuration
	public Option[] localEmbeddedH2XATxConfiguration() {
		String localRepo = System.getProperty("maven.repo.local");
		if (localRepo == null) {
			localRepo = System.getProperty("org.ops4j.pax.url.mvn.localRepository");
		}
		
		Option testSpecificOptions = testSpecificOptions();
		
		return options(junitBundles(), systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value("INFO"),
				when(localRepo != null)
						.useOptions(CoreOptions.vmOption("-Dorg.ops4j.pax.url.mvn.localRepository=" + localRepo)),
				xaTxControlService(),
				localJdbcResourceProviderWithH2(),
				when(testSpecificOptions != null).useOptions(testSpecificOptions),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-api").versionAsInProject(),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-service").versionAsInProject()
				
//				,CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
		);
	}

	@Configuration
	public Option[] localServerH2XATxConfiguration() {
		String localRepo = System.getProperty("maven.repo.local");
		if (localRepo == null) {
			localRepo = System.getProperty("org.ops4j.pax.url.mvn.localRepository");
		}
		
		Option testSpecificOptions = testSpecificOptions();
		
		return options(junitBundles(), systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value("INFO"),
				when(localRepo != null)
				.useOptions(CoreOptions.vmOption("-Dorg.ops4j.pax.url.mvn.localRepository=" + localRepo)),
				xaTxControlService(),
				localJdbcResourceProviderWithH2(),
				systemProperty(REMOTE_DB_PROPERTY).value(getRemoteDBPath()),
				when(testSpecificOptions != null).useOptions(testSpecificOptions),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-api").versionAsInProject(),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-service").versionAsInProject()
				
//				,CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
				);
	}

	@Configuration
	public Option[] localConfigAdminDrivenH2XATxConfiguration() {
		String localRepo = System.getProperty("maven.repo.local");
		if (localRepo == null) {
			localRepo = System.getProperty("org.ops4j.pax.url.mvn.localRepository");
		}
		
		Option testSpecificOptions = testSpecificOptions();
		
		return options(junitBundles(), systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value("INFO"),
				when(localRepo != null)
				.useOptions(CoreOptions.vmOption("-Dorg.ops4j.pax.url.mvn.localRepository=" + localRepo)),
				xaTxControlService(),
				localJdbcResourceProviderWithH2(),
				systemProperty(REMOTE_DB_PROPERTY).value(getRemoteDBPath()),
				mavenBundle("org.apache.felix", "org.apache.felix.configadmin").versionAsInProject(),
				systemProperty(CONFIGURED_PROVIDER_PROPERTY).value("local"),
				when(testSpecificOptions != null).useOptions(testSpecificOptions),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-api").versionAsInProject(),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-service").versionAsInProject()
				
//				,CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
				);
	}
	
	@Configuration
	public Option[] xaServerH2XATxConfiguration() {
		String localRepo = System.getProperty("maven.repo.local");
		if (localRepo == null) {
			localRepo = System.getProperty("org.ops4j.pax.url.mvn.localRepository");
		}
		
		Option testSpecificOptions = testSpecificOptions();
		
		return options(junitBundles(), systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value("INFO"),
				when(localRepo != null)
				.useOptions(CoreOptions.vmOption("-Dorg.ops4j.pax.url.mvn.localRepository=" + localRepo)),
				xaTxControlService(),
				xaJdbcResourceProviderWithH2(),
				systemProperty(REMOTE_DB_PROPERTY).value(getRemoteDBPath()),
				when(testSpecificOptions != null).useOptions(testSpecificOptions),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-api").versionAsInProject(),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-service").versionAsInProject()
				
//				,CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
				);
	}

	@Configuration
	public Option[] xaConfigAdminDrivenH2XATxConfiguration() {
		String localRepo = System.getProperty("maven.repo.local");
		if (localRepo == null) {
			localRepo = System.getProperty("org.ops4j.pax.url.mvn.localRepository");
		}
		
		Option testSpecificOptions = testSpecificOptions();
		
		return options(junitBundles(), systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value("INFO"),
				when(localRepo != null)
				.useOptions(CoreOptions.vmOption("-Dorg.ops4j.pax.url.mvn.localRepository=" + localRepo)),
				xaTxControlService(),
				xaJdbcResourceProviderWithH2(),
				systemProperty(REMOTE_DB_PROPERTY).value(getRemoteDBPath()),
				mavenBundle("org.apache.felix", "org.apache.felix.configadmin").versionAsInProject(),
				systemProperty(CONFIGURED_PROVIDER_PROPERTY).value("xa"),
				when(testSpecificOptions != null).useOptions(testSpecificOptions),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-api").versionAsInProject(),
				mavenBundle("org.ops4j.pax.logging", "pax-logging-service").versionAsInProject()
				
//				,CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005")
				);
	}

	private String getRemoteDBPath() {
		String fullResourceName = getClass().getName().replace('.', '/') + ".class";
		
		String resourcePath = getClass().getResource(getClass().getSimpleName() + ".class").getPath();
		
		File testClassesDir = new File(resourcePath.substring(0, resourcePath.length() - fullResourceName.length()));
		
		String dbPath = new File(testClassesDir.getParentFile(), "testdb/db1").getAbsolutePath();
		return dbPath;
	}
	
	public Option localTxControlService() {
		return CoreOptions.composite(
				systemProperty(TX_CONTROL_FILTER).value("(!(osgi.xa.enabled=*))"),
				mavenBundle("org.apache.aries.tx-control", "tx-control-service-local").versionAsInProject());
	}

	public Option xaTxControlService() {
		return CoreOptions.composite(
				systemProperty(TX_CONTROL_FILTER).value("(osgi.xa.enabled=true)"),
				mavenBundle("org.apache.aries.tx-control", "tx-control-service-xa").versionAsInProject());
	}

	public Option localJdbcResourceProviderWithH2() {
		return CoreOptions.composite(
				mavenBundle("com.h2database", "h2").versionAsInProject(),
				mavenBundle("org.apache.aries.tx-control", "tx-control-provider-jdbc-local").versionAsInProject());
	}

	public Option xaJdbcResourceProviderWithH2() {
		return CoreOptions.composite(
				mavenBundle("com.h2database", "h2").versionAsInProject(),
				mavenBundle("org.apache.aries.tx-control", "tx-control-provider-jdbc-xa").versionAsInProject());
	}

	protected Option testSpecificOptions() {
		return null;
	}
}
