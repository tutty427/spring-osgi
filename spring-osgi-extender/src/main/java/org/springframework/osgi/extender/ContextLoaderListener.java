/*
 * Copyright 2002-2006 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package org.springframework.osgi.extender;

import java.net.URL;
import java.util.HashMap;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;
import org.osgi.framework.SynchronousBundleListener;
import org.springframework.beans.factory.xml.PluggableSchemaResolver;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.osgi.context.support.DefaultOsgiBundleXmlApplicationContextFactory;
import org.springframework.osgi.context.support.OsgiBundleXmlApplicationContextFactory;
import org.springframework.osgi.context.support.OsgiPlatformDetector;
import org.springframework.osgi.extender.support.ApplicationContextCloser;
import org.springframework.osgi.extender.support.ApplicationContextCreator;
import org.springframework.osgi.extender.support.NamespacePlugins;
import org.springframework.osgi.io.OsgiBundleResource;

/**
 * Osgi Extender that listens to bundle events and manages the creation and destruction
 * of application contexts for bundles that have one or both of:
 * <ul>
 *   <li> A manifest header entry Spring-Context
 *   <li> XML files in META-INF/spring
 * </ul>
 * <p>The extender also discovers any Spring namespace handlers in resolved bundles
 * and publishes a namespace resolving service for each.</p>
 * 
 * <p>If a fragment is attached to the extender bundle that defines a resource
 * META-INF/spring/extender.xml then this file will be used to create an application
 * context for configuring the extender bundle itself. By defining a bean named
 * "taskExecutor" in that context you can configure how the extender bundle schedules
 * asynchronous activity. The extender context is created during a synchronous 
 * OSGi lifecycle callback and should contain only simple bean definitions that will not
 * delay context initialisation.</p>
 *
 * @author Bill Gallagher
 * @author Andy Piper
 * @author Hal Hildebrand
 * @author Adrian Colyer
 */
public class ContextLoaderListener implements BundleActivator, SynchronousBundleListener
{
	// The standard for META-INF header keys excludes ".", so these constants
	// must use "-"
	private static final String SPRING_HANDLER_MAPPINGS_LOCATION = "META-INF/spring.handlers";
	private static final String EXTENDER_CONFIG_FILE_LOCATION = "META-INF/spring/extender.xml";
	private static final String[] OSGI_BUNDLE_RESOLVER_INTERFACE_NAME = {"org.springframework.osgi.context.support.OsgiBundleNamespaceHandlerAndEntityResolver"};
	private static final String TASK_EXECUTOR_BEAN_NAME = "taskExecutor";
	
	private static final Log log = LogFactory.getLog(ContextLoaderListener.class);

	/**
	 * The id of the extender bundle itself
	 */
	private long bundleId;

	/**
	 * Factory to use for creating application contexts on behalf of 
	 * managed bundles
	 */
	private OsgiBundleXmlApplicationContextFactory contextFactory;
	
	/**
	 * Context created to configure the extender bundle itself (currently
	 * only used for overriding task executor implementation).
	 */
	private ConfigurableApplicationContext extenderContext;

	/**
	 * The bundles we are currently managing. Keys are bundle ids, values are
	 * application contexts
	 */
	private Map managedBundles;
	
	/**
	 * ApplicationContexts which are being initialized by an ApplicationContextCreator,
	 * but have not yet completed initialization (for example, they are waiting on 
	 * service dependencies). Keys are bundle ids, values are application contexts 
	 */
	private Map applicationContextsBeingInitialized;

	/**
	 * ServiceRegistration object returned by OSGi when registering the NamespacePlugins
	 * instance as a service
	 */
	private ServiceRegistration resolverServiceRegistration = null;
	
	/**
	 * Are we running under knoplerfish? Required for bug workaround with calling
	 * getResource under KF
	 */
	private boolean isKnopflerfish = false;

	/**
	 * The set of all namespace plugins known to the extender
	 */
	private NamespacePlugins namespacePlugins;
	
	/**
	 * Task executor used for kicking off background activity
	 */
	private TaskExecutor taskExecutor;
		
	/*
	 *  Required by the BundleActivator contract
	 */
	public ContextLoaderListener() {
	}

	/**
	 * <p>
	 * Called by OSGi when this bundle is started. Finds all previously
	 * resolved bundles and adds namespace handlers for them if necessary.</p>
	 * <p>
	 * Does <em>not</em> create application contexts for bundles started
	 * before the extender was started.</p>
	 * <p>
	 * Registers a namespace/entity resolving service for use by web app
	 * contexts.</p>
	 * 
	 * @see org.osgi.framework.BundleActivator.start
	 */
	public void start(BundleContext context) throws Exception {
		if (log.isInfoEnabled()) {
			log.info("Starting org.springframework.osgi.extender bundle");
		}
		
		this.isKnopflerfish = OsgiPlatformDetector.isKnopflerfish(context);
		this.bundleId = context.getBundle().getBundleId();
		this.managedBundles = new HashMap();
		this.applicationContextsBeingInitialized = new HashMap();
		this.namespacePlugins = new NamespacePlugins();
		this.contextFactory = new DefaultOsgiBundleXmlApplicationContextFactory();
		
		// Collect all previously resolved bundles which have namespace plugins
		Bundle[] previousBundles = context.getBundles();
		for (int i = 0; i < previousBundles.length; i++) {
			maybeAddNamespaceHandlerFor(previousBundles[i]);
		}
		
		this.resolverServiceRegistration = registerResolverService(context);
		// do this once namespace handlers have been detected
		this.taskExecutor = createTaskExecutor(context);

		// listen to any changes in bundles
		context.addBundleListener(this);
	}

	/**
	 * Called by OSGi when this bundled is stopped. Unregister
	 * the namespace/entity resolving service and clear all state.
	 * No further management of application contexts created by this
	 * extender prior to stopping the bundle occurs after this point 
	 * (even if the extender bundle is subsequently restarted).
	 * 
	 * @see org.osgi.framework.BundleActivator.stop
	 */
	public void stop(BundleContext context) throws Exception {
		if (log.isInfoEnabled()) {
			log.info("Stopping org.springframework.osgi.extender bundle");
		}
		
		unregisterResolverService();
		this.managedBundles = null;
		this.applicationContextsBeingInitialized = null;
		this.contextFactory = null;
		this.namespacePlugins = null;
		this.taskExecutor = null;
		if (this.extenderContext != null) {
			this.extenderContext.close();
			this.extenderContext = null;
		}
	}

	/**
	 * Register the NamespacePlugins instance as an Osgi Resolver service
	 * @return
	 */
	private ServiceRegistration registerResolverService(BundleContext context) {
		if (log.isInfoEnabled()) {
			log.info("Registering Spring NamespaceHandler and EntityResolver service");
		}
		
		return context.registerService(OSGI_BUNDLE_RESOLVER_INTERFACE_NAME, this.namespacePlugins, null);
	}

	
	/**
	 * Unregister the NamespaceHandler and EntityResolver service
	 */
	private void unregisterResolverService() {
		if (log.isInfoEnabled()) {
			log.info("Unregistering Spring NamespaceHandler and EntityResolver service");
		}

		try {
			if (this.resolverServiceRegistration != null) {
				this.resolverServiceRegistration.unregister();
			}
			this.resolverServiceRegistration = null;
		}
		catch (IllegalStateException ex) {
			// service has already been unregistered
			// this should not happen, but neither should it impede our
			// progress if it does
		}
	}


	/**
	 * A bundle has been started, stopped, resolved, or unresolved.
	 * This method is a synchronous callback, do not do any
	 * long-running work in this thread.
	 * 
	 * @see org.osgi.framework.SynchronousBundleListener.bundleChanged
	 */
	public void bundleChanged(BundleEvent event) {
		if (event.getBundle().getBundleId() == bundleId) {
			return;
		}

		switch (event.getType()) {
			case BundleEvent.STARTED:
				maybeCreateApplicationContextFor(event.getBundle());
				break;
			case BundleEvent.STOPPING:
				maybeCloseApplicationContextFor(event.getBundle());
				break;
			case BundleEvent.RESOLVED:
				maybeAddNamespaceHandlerFor(event.getBundle());
				break;
			case BundleEvent.UNRESOLVED:
				maybeRemoveNameSpaceHandlerFor(event.getBundle());
				break;
		}
	}

	/**
	 * Context creation is a potentially long-running activity (certainly more
	 * than we want to do on the synchronous event callback). Kick off a background
	 * activity to create an application context for the given bundle if needed.
	 * 
	 * @param bundle
	 */
	private void maybeCreateApplicationContextFor(Bundle bundle) {
		ApplicationContextCreator contextCreator = 
			new ApplicationContextCreator(
					bundle,
					this.managedBundles,
					this.applicationContextsBeingInitialized,
					this.contextFactory,
					this.namespacePlugins);
		this.taskExecutor.execute(contextCreator);
	}

	/**
	 * Closing an application context is a potentially long-running activity, however,
	 * we *have* to do it synchronously during the event process as the BundleContext
	 * object is not valid once we return from this method.
	 * 
	 * @param bundle
	 */
	private void maybeCloseApplicationContextFor(Bundle bundle) {
		ApplicationContextCloser contextCloser = 
			new ApplicationContextCloser(bundle,this.managedBundles,this.applicationContextsBeingInitialized);
		contextCloser.run();
	}

	/**
	 * If this bundle defines handler mapping or schema mapping resources, then register it with the
	 * namespace plugin handler.
	 * 
	 * @param bundle
	 */
	private void maybeAddNamespaceHandlerFor(Bundle bundle) {
        if (Constants.SYSTEM_BUNDLE_SYMBOLICNAME.equals(bundle.getSymbolicName())) {
            return;  // Do not resolve namespace and entity handlers from the system bundle
        }

        if (isKnopflerfish) {
			// knopflerfish (2.0.0) has a bug #1581187 which gives a classcast exception if you call getResource
			// from outside of the bundle, yet getResource works bettor on equinox....
			// see http://sourceforge.net/tracker/index.php?func=detail&aid=1581187&group_id=82798&atid=567241
			if (bundle.getEntry(SPRING_HANDLER_MAPPINGS_LOCATION) != null
					|| bundle.getEntry(PluggableSchemaResolver.DEFAULT_SCHEMA_MAPPINGS_LOCATION) != null) {
				addHandler(bundle);
			}
		} else {
			if (bundle.getResource(SPRING_HANDLER_MAPPINGS_LOCATION) != null
					|| bundle.getResource(PluggableSchemaResolver.DEFAULT_SCHEMA_MAPPINGS_LOCATION) != null) {
				addHandler(bundle);
			}
		}
	}

	/**
	 * Add this bundle to those known to provide handler or schema mappings
	 * 
	 * @param bundle
	 */
	private void addHandler(Bundle bundle) {
		if (log.isInfoEnabled()) {
			log.info("Adding namespace handler resolver for " + bundle.getSymbolicName());
		}

		this.namespacePlugins.addHandler(bundle);
	}

	/**
	 * Remove this bundle from the set of those known to provide handler or schema
	 * mappings.
	 * 
	 * @param bundle
	 */
	private void maybeRemoveNameSpaceHandlerFor(Bundle bundle) {
		boolean removed = this.namespacePlugins.removeHandler(bundle);
		if (removed && log.isInfoEnabled()) {
			log.info("Removed namespace handler resolver for " + bundle.getSymbolicName());
		}
	}

	/**
	 * <p>
	 * Create the task executor to be used for any asynchronous activity kicked off by this
	 * bundle. By default an <code>org.springframework.core.task.SimpleAsyncTaskExecutor</code>
	 * will be used. This should be sufficient for most purposes.</p>
	 * <p>
	 * It is possible to configure the extender bundle to use an alternate task executor
	 * implementation (for example, a CommonJ WorkManager based implementation when running
	 * under WLS or WebSphere). To do this attach a fragment to the extender bundle that defines
	 * a Spring application context configuration file in META-INF/spring/extender.xml. If such
	 * a resource exists, then an application context will be created from that configuration file,
	 * and a bean named "taskExecutor" will be looked up by name. If such a bean exists, it will
	 * be used.</p>
	 * 
	 * @param context
	 * @return
	 */
	private TaskExecutor createTaskExecutor(BundleContext context) {
		Bundle extenderBundle = context.getBundle();
		URL extenderConfigFile = extenderBundle.getResource(EXTENDER_CONFIG_FILE_LOCATION);
		if (extenderConfigFile != null) {
			String[] locations = new String[] { 
					OsgiBundleResource.BUNDLE_URL_URL_PREFIX + extenderConfigFile.toExternalForm()	};
			this.extenderContext = this.contextFactory.createApplicationContextWithBundleContext(
					context, locations, this.namespacePlugins, false);
			extenderContext.refresh();

			if (extenderContext.containsBean(TASK_EXECUTOR_BEAN_NAME)) {
				Object taskExecutor = extenderContext.getBean(TASK_EXECUTOR_BEAN_NAME);
				if (taskExecutor instanceof TaskExecutor) {
					return (TaskExecutor) taskExecutor;
				}
				else {
					if (log.isErrorEnabled()) {
						log.error("Bean 'taskExecutor' in META-INF/spring/extender.xml configuration file " +
								  "is not an instance of org.springframework.core.task.TaskExecutor. " +
								  "Defaulting to SimpleAsyncTaskExecutor.");
					}					
				}
			}
			else {
				if (log.isWarnEnabled()) {
					log.warn("Found META-INF/spring/extender.xml configuration file, but no bean " +
							"named 'taskExecutor' was defined, defaulting to SimpleAsyncTaskExecutor.");
				}
			}
		}
		
		return new SimpleAsyncTaskExecutor(extenderBundle.getSymbolicName());
	}
}