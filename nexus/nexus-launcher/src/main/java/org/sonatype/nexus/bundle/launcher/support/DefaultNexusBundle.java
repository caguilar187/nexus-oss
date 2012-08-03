/**
 * Sonatype Nexus (TM) Open Source Version
 * Copyright (c) 2007-2012 Sonatype, Inc.
 * All rights reserved. Includes the third-party code listed at http://links.sonatype.com/products/nexus/oss/attributions.
 *
 * This program and the accompanying materials are made available under the terms of the Eclipse Public License Version 1.0,
 * which accompanies this distribution and is available at http://www.eclipse.org/legal/epl-v10.html.
 *
 * Sonatype Nexus (TM) Professional Version is available from Sonatype, Inc. "Sonatype" and "Sonatype Nexus" are trademarks
 * of Sonatype, Inc. Apache Maven is a trademark of the Apache Software Foundation. M2eclipse is a trademark of the
 * Eclipse Foundation. All other trademarks are the property of their respective owners.
 */
package org.sonatype.nexus.bundle.launcher.support;

import static java.lang.String.format;
import static org.sonatype.nexus.bootstrap.CommandMonitorTalker.LOCALHOST;
import static org.sonatype.nexus.bootstrap.commands.StopApplicationCommand.STOP_APPLICATION_COMMAND;
import static org.sonatype.nexus.bootstrap.commands.StopMonitorCommand.STOP_MONITOR_COMMAND;
import static org.sonatype.sisu.filetasks.FileTaskRunner.onDirectory;
import static org.sonatype.sisu.filetasks.builder.FileRef.file;
import static org.sonatype.sisu.filetasks.builder.FileRef.path;
import static org.sonatype.sisu.jsw.util.JSWConfig.WRAPPER_JAVA_ADDITIONAL;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import org.apache.tools.ant.taskdefs.condition.Os;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonatype.nexus.bootstrap.CommandMonitorTalker;
import org.sonatype.nexus.bootstrap.CommandMonitorThread;
import org.sonatype.nexus.bootstrap.Launcher;
import org.sonatype.nexus.bootstrap.commands.PingCommand;
import org.sonatype.nexus.bootstrap.commands.StopMonitorCommand;
import org.sonatype.nexus.bundle.launcher.NexusBundle;
import org.sonatype.nexus.bundle.launcher.NexusBundleConfiguration;
import org.sonatype.sisu.bl.support.DefaultWebBundle;
import org.sonatype.sisu.bl.support.RunningBundles;
import org.sonatype.sisu.bl.support.port.PortReservationService;
import org.sonatype.sisu.filetasks.FileTaskBuilder;
import org.sonatype.sisu.jsw.util.JSWConfig;
import com.google.common.base.Throwables;

/**
 * Default Nexus bundle implementation.
 *
 * @since 2.0
 */
@Named
public class DefaultNexusBundle
    extends DefaultWebBundle<NexusBundle, NexusBundleConfiguration>
    implements NexusBundle
{

    private static final Logger log = LoggerFactory.getLogger( DefaultNexusBundle.class );

    /**
     * File task builder.
     * Cannot be null.
     */
    private final FileTaskBuilder fileTaskBuilder;

    /**
     * Port on which Nexus Command Monitor is running. 0 (zero) if application is not running.
     */
    private int commandMonitorPort;

    /**
     * Port on which Nexus Keep Alive is running. 0 (zero) if application is not running.
     */
    private int keepAlivePort;

    /**
     * Keep alive thread. Null if server is not started.
     */
    private CommandMonitorThread keepAliveThread;

    @Inject
    public DefaultNexusBundle( final Provider<NexusBundleConfiguration> configurationProvider,
                               final RunningBundles runningBundles,
                               final FileTaskBuilder fileTaskBuilder,
                               final PortReservationService portReservationService )
    {
        super( "nexus", configurationProvider, runningBundles, fileTaskBuilder, portReservationService );
        this.fileTaskBuilder = fileTaskBuilder;
    }

    /**
     * Additionally <br/>
     * - configures Nexus/Jetty port<br/>
     * - installs command monitor<br/>
     * - installs keep alive monitor<br/>
     * - configure remote debugging if requested<br/>
     * - installs plugins.
     * <p/>
     * {@inheritDoc}
     *
     * @since 2.0
     */
    @Override
    protected void configure()
        throws Exception
    {
        super.configure();

        commandMonitorPort = getPortReservationService().reservePort();
        keepAlivePort = getPortReservationService().reservePort();

        final ConfigurationStrategy strategy = determineConfigurationStrategy();

        configureJSW( strategy );
        configureNexusProperties( strategy );
        installPlugins();
    }

    /**
     * {@inheritDoc}
     *
     * @since 1.0
     */
    @Override
    protected void unconfigure()
    {
        super.unconfigure();

        if ( commandMonitorPort > 0 )
        {
            getPortReservationService().cancelPort( commandMonitorPort );
            commandMonitorPort = 0;
        }
        if ( keepAlivePort > 0 )
        {
            getPortReservationService().cancelPort( keepAlivePort );
            keepAlivePort = 0;
        }
    }

    /**
     * Starts Nexus using JSW.
     * <p/>
     * {@inheritDoc}
     *
     * @since 2.0
     */
    @Override
    protected void startApplication()
    {
        try
        {
            keepAliveThread = new CommandMonitorThread(
                keepAlivePort,
                new PingCommand(),
                new StopMonitorCommand()
            );
            keepAliveThread.start();
        }
        catch ( IOException e )
        {
            throw new RuntimeException( "Could not start JSW keep alive thread", e );
        }
        installStopShutdownHook( commandMonitorPort );

        final File nexusDir = getNexusDirectory();

        makeExecutable( nexusDir, "nexus" );
        makeExecutable( nexusDir, "wrapper" );

        onDirectory( nexusDir ).apply(
            fileTaskBuilder.exec().spawn()
                .script( path( "bin/nexus" ) )
                .withArgument( "console" )
        );
    }

    /**
     * Stops Nexus using JSW.
     * <p/>
     * {@inheritDoc}
     *
     * @since 2.0
     */
    @Override
    protected void stopApplication()
    {
        try
        {
            sendStopToNexus( commandMonitorPort );
        }
        finally
        {
            if ( keepAliveThread != null )
            {
                sendStopToKeepAlive( keepAlivePort );
                keepAliveThread = null;
            }
        }
    }

    /**
     * Checks if Nexus is alive by using REST status service.
     *
     * @return true if Nexus is alive
     * @since 2.0
     */
    @Override
    protected boolean applicationAlive()
    {
        try
        {
            return RequestUtils.isNexusRESTStarted( getUrl().toExternalForm() );
        }
        catch ( IOException ignore )
        {
            return false;
        }
    }

    /**
     * Configure Nexus properties using provided configuration strategy.
     *
     * @param strategy configuration strategy
     */
    private void configureNexusProperties( final ConfigurationStrategy strategy )
    {
        strategy.configureNexus();
    }

    /**
     * Configure JSW properties using provided configuration strategy.
     *
     * @param strategy configuration strategy
     */
    private void configureJSW( final ConfigurationStrategy strategy )
    {
        try
        {
            final NexusBundleConfiguration config = getConfiguration();

            final File jswConfigFile = new File( config.getTargetDirectory(), "nexus/bin/jsw/conf/wrapper.conf" );

            final JSWConfig jswConfig = new JSWConfig(
                jswConfigFile,
                "The following properties are added by Nexus IT as an override of properties already configured"
            ).load();

            strategy.configureJSW( jswConfig );

            jswConfig.save();
        }
        catch ( final IOException e )
        {
            throw Throwables.propagate( e );
        }
    }

    /**
     * Install Nexus plugins in {@code sonatype-work/nexus/plugin-repository}.
     */
    private void installPlugins()
    {
        NexusBundleConfiguration config = getConfiguration();
        List<File> plugins = config.getPlugins();
        for ( File plugin : plugins )
        {
            if ( plugin.isDirectory() )
            {
                onDirectory( config.getTargetDirectory() ).apply(
                    fileTaskBuilder.copy()
                        .directory( file( plugin ) )
                        .to().directory( path( "sonatype-work/nexus/plugin-repository" ) )
                );
            }
            else
            {
                onDirectory( config.getTargetDirectory() ).apply(
                    fileTaskBuilder.expand( file( plugin ) )
                        .to().directory( path( "sonatype-work/nexus/plugin-repository" ) )
                );
            }
        }
    }

    /**
     * Determines a configuration strategy based on version of Nexus to be started.
     *
     * @return configuration strategy. Never null.
     */
    private ConfigurationStrategy determineConfigurationStrategy()
    {
        final File libDir = new File( getConfiguration().getTargetDirectory(), "nexus/lib" );
        // nexus-bootstrap-<version>.jar is only present starting with version 2.1
        final String[] nexusBootstrapJarsBiggerThen2Dot1 = libDir.list( new FilenameFilter()
        {
            @Override
            public boolean accept( final File dir, final String name )
            {
                return name.startsWith( "nexus-" )
                    && name.endsWith( ".jar" )
                    && !"nexus-bootstrap-2.1.jar".equals( name );
            }

        } );
        if ( nexusBootstrapJarsBiggerThen2Dot1 != null && nexusBootstrapJarsBiggerThen2Dot1.length > 0 )
        {
            return new CS22AndAbove();
        }
        return new CS21AndBellow();
    }

    @Override
    public File getWorkDirectory()
    {
        return new File( getConfiguration().getTargetDirectory(), "sonatype-work/nexus" );
    }

    public File getNexusDirectory()
    {
        return new File( getConfiguration().getTargetDirectory(), "nexus" );
    }

    @Override
    protected String generateId()
    {
        return getName() + "-" + System.currentTimeMillis();
    }

    private void makeExecutable( final File baseDir,
                                 final String scriptName )
    {
        if ( !Os.isFamily( Os.FAMILY_WINDOWS ) )
        {
            onDirectory( baseDir ).apply(
                fileTaskBuilder.chmod( path( "/" ) )
                    .include( "**/" + scriptName )
                    .permissions( "u+x" )
            );
        }
    }

    private static void installStopShutdownHook( final int commandPort )
    {
        Thread stopShutdownHook = new Thread( "JSW Sanity Stopper" )
        {
            @Override
            public void run()
            {
                sendStopToNexus( commandPort );
            }
        };

        Runtime.getRuntime().addShutdownHook( stopShutdownHook );
        log.debug( "Installed stop shutdown hook" );
    }

    private static void sendStopToNexus( final int commandPort )
    {
        log.debug( "Sending stop command to Nexus" );
        try
        {
            new CommandMonitorTalker( LOCALHOST, commandPort ).send( STOP_APPLICATION_COMMAND );
        }
        catch ( Exception e )
        {
            log.debug(
                "Skipping exception got while sending stop command to Nexus {}:{}",
                e.getClass().getName(), e.getMessage()
            );
        }
    }

    private static void sendStopToKeepAlive( final int commandPort )
    {
        log.debug( "Sending stop command to keep alive thread" );
        try
        {
            new CommandMonitorTalker( LOCALHOST, commandPort ).send( STOP_MONITOR_COMMAND );
        }
        catch ( Exception e )
        {
            log.debug(
                "Skipping exception got while sending stop command to keep alive thread {}:{}",
                e.getClass().getName(), e.getMessage()
            );
        }
    }

    private static interface ConfigurationStrategy
    {

        void configureJSW( JSWConfig jswConfig );

        void configureNexus();
    }

    private class CS22AndAbove
        implements ConfigurationStrategy
    {

        @Override
        public void configureJSW( final JSWConfig jswConfig )
        {
            // configure remote debug if requested
            if ( getConfiguration().getDebugPort() > 0 )
            {
                jswConfig.addIndexedProperty( WRAPPER_JAVA_ADDITIONAL, "-Xdebug" );
                jswConfig.addIndexedProperty( WRAPPER_JAVA_ADDITIONAL, "-Xnoagent" );
                jswConfig.addIndexedProperty( WRAPPER_JAVA_ADDITIONAL, "-Djava.compiler=NONE" );
                jswConfig.addIndexedProperty( WRAPPER_JAVA_ADDITIONAL,
                                              "-Xrunjdwp:transport=dt_socket,server=y,suspend="
                                                  + ( getConfiguration().isSuspendOnStart() ? "y" : "n" )
                                                  + ",address=" + getConfiguration().getDebugPort() );
            }
        }

        @Override
        public void configureNexus()
        {
            final Properties nexusProperties = new Properties();

            nexusProperties.setProperty( "application-port", String.valueOf( getPort() ) );
            nexusProperties.setProperty( Launcher.COMMAND_MONITOR_PORT, String.valueOf( commandMonitorPort ) );
            nexusProperties.setProperty( Launcher.KEEP_ALIVE_PORT, String.valueOf( keepAlivePort ) );

            final Map<String, String> systemProperties = getConfiguration().getSystemProperties();
            if ( !systemProperties.isEmpty() )
            {
                for ( final Map.Entry<String, String> entry : systemProperties.entrySet() )
                {
                    nexusProperties.setProperty( entry.getKey(), entry.getValue() == null ? "true" : entry.getValue() );
                }
            }

            onDirectory( getConfiguration().getTargetDirectory() ).apply(
                fileTaskBuilder.properties( path( "nexus/conf/nexus-test.properties" ) )
                    .properties( nexusProperties )
            );
        }

    }

    private class CS21AndBellow
        implements ConfigurationStrategy
    {

        @Override
        public void configureJSW( final JSWConfig jswConfig )
        {
            jswConfig.configureMonitor( commandMonitorPort );
            jswConfig.configureKeepAlive( keepAlivePort );

            final Map<String, String> systemProperties = getConfiguration().getSystemProperties();
            if ( !systemProperties.isEmpty() )
            {
                for ( final Map.Entry<String, String> entry : systemProperties.entrySet() )
                {
                    jswConfig.addIndexedProperty(
                        WRAPPER_JAVA_ADDITIONAL,
                        format( "-D%s=%s", entry.getKey(), entry.getValue() == null ? "true" : entry.getValue() )
                    );
                }
            }

            // configure remote debug if requested
            if ( getConfiguration().getDebugPort() > 0 )
            {
                jswConfig.addIndexedProperty( WRAPPER_JAVA_ADDITIONAL, "-Xdebug" );
                jswConfig.addIndexedProperty( WRAPPER_JAVA_ADDITIONAL, "-Xnoagent" );
                jswConfig.addIndexedProperty( WRAPPER_JAVA_ADDITIONAL, "-Djava.compiler=NONE" );
                jswConfig.addIndexedProperty( WRAPPER_JAVA_ADDITIONAL,
                                              "-Xrunjdwp:transport=dt_socket,server=y,suspend="
                                                  + ( getConfiguration().isSuspendOnStart() ? "y" : "n" )
                                                  + ",address=" + getConfiguration().getDebugPort() );
            }
        }

        @Override
        public void configureNexus()
        {
            onDirectory( getConfiguration().getTargetDirectory() ).apply(
                fileTaskBuilder.properties( path( "nexus/conf/nexus.properties" ) )
                    .property( "application-port", String.valueOf( getPort() ) )
            );
        }

    }

}
