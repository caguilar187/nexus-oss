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
package org.sonatype.nexus.bundle.launcher.transformers;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.sonatype.sisu.maven.bridge.support.ModelBuildingRequestBuilder.model;

import java.util.Map;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuildingException;
import org.sonatype.nexus.bundle.launcher.Filter;
import org.sonatype.sisu.maven.bridge.MavenModelResolver;
import com.google.common.collect.Maps;

/**
 * Replaces placeholders with values out of test project pom: <br/>
 * - ${project.groupId}
 * - ${project.artifactId}
 * - ${project.version}
 *
 * @since 2.2
 */
@Named
@Singleton
public class TestProjectFilter
    extends MapFilterSupport
    implements Filter
{

    public static final String TEST_PROJECT_POM_FILE = "testProjectPomFile";

    /**
     * Model resolver used to resolve effective model of test project (pom).
     * Never null.
     */
    private final MavenModelResolver modelResolver;

    /**
     * Constructor.
     *
     * @param modelResolver Model resolver used to resolve effective model of test project (pom). Cannot be null.
     */
    @Inject
    public TestProjectFilter( @Named( "remote-model-resolver-using-settings" )
                              final MavenModelResolver modelResolver )
    {
        this.modelResolver = checkNotNull( modelResolver );
    }

    /**
     * Returns mappings by extracting testing project model properties.
     *
     * @param context filtering context. Cannot be null.
     * @return mappings extracted from project under test model
     */
    @Override
    Map<String, String> mappings( final Map<String, String> context )
    {
        final Map<String, String> mappings = Maps.newHashMap();

        final String testProjectPomFile = context.get( TEST_PROJECT_POM_FILE );
        if ( testProjectPomFile == null )
        {
            // TODO log a warning?
        }
        else
        {
            try
            {
                final Model model = modelResolver.resolveModel( model().pom( testProjectPomFile ) );

                mappings.put( "project.groupId", model.getGroupId() );
                mappings.put( "project.artifactId", model.getArtifactId() );
                mappings.put( "project.version", model.getVersion() );

                if ( model.getProperties() != null )
                {
                    mappings.putAll( Maps.fromProperties( model.getProperties() ) );
                }

                return mappings;
            }
            catch ( ModelBuildingException e )
            {
                // TODO log?
            }
        }
        return mappings;
    }

}
