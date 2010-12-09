package org.sonatype.nexus.plugins.mac;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Arrays;

import org.apache.lucene.search.Query;
import org.apache.maven.archetype.catalog.Archetype;
import org.apache.maven.archetype.catalog.ArchetypeCatalog;
import org.apache.maven.archetype.catalog.io.xpp3.ArchetypeCatalogXpp3Writer;
import org.apache.maven.index.AndMultiArtifactInfoFilter;
import org.apache.maven.index.ArtifactInfo;
import org.apache.maven.index.ArtifactInfoFilter;
import org.apache.maven.index.IteratorSearchRequest;
import org.apache.maven.index.IteratorSearchResponse;
import org.apache.maven.index.MAVEN;
import org.apache.maven.index.NexusIndexer;
import org.apache.maven.index.SearchType;
import org.apache.maven.index.context.IndexingContext;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.util.StringUtils;

@Component( role = MacPlugin.class )
public class DefaultMacPlugin
    implements MacPlugin
{
    @Requirement
    private NexusIndexer indexer;

    private ArchetypeCatalogXpp3Writer writer = new ArchetypeCatalogXpp3Writer();

    /**
     * Lists available archatypes for given request.
     * 
     * @param request
     * @return
     * @throws NoSuchRepositoryException
     * @throws IOException
     */
    protected IteratorSearchResponse listArchetypes( MacRequest request, IndexingContext ctx )
        throws IOException
    {
        Query pq = indexer.constructQuery( MAVEN.PACKAGING, "maven-archetype", SearchType.EXACT );

        // to have sorted results by version in descending order
        IteratorSearchRequest sreq = new IteratorSearchRequest( pq, ctx );

        // filter that filters out classified artifacts
        ClassifierArtifactInfoFilter classifierFilter = new ClassifierArtifactInfoFilter();

        // combine it with others if needed (unused in cli, but perm filtering in server!)
        if ( request.getArtifactInfoFilter() != null )
        {
            AndMultiArtifactInfoFilter andArtifactFilter =
                new AndMultiArtifactInfoFilter( Arrays.asList( new ArtifactInfoFilter[] { classifierFilter,
                    request.getArtifactInfoFilter() } ) );

            sreq.setArtifactInfoFilter( andArtifactFilter );
        }
        else
        {
            sreq.setArtifactInfoFilter( classifierFilter );
        }

        IteratorSearchResponse hits = indexer.searchIterator( sreq );

        return hits;
    }

    public ArchetypeCatalog listArcherypesAsCatalog( MacRequest request, IndexingContext ctx )
        throws IOException
    {
        IteratorSearchResponse infos = listArchetypes( request, ctx );

        try
        {
            ArchetypeCatalog catalog = new ArchetypeCatalog();

            Archetype archetype = null;

            // fill it in
            for ( ArtifactInfo info : infos )
            {
                archetype = new Archetype();
                archetype.setGroupId( info.groupId );
                archetype.setArtifactId( info.artifactId );
                archetype.setVersion( info.version );
                archetype.setDescription( info.description );

                if ( StringUtils.isNotEmpty( request.getRepositoryUrl() ) )
                {
                    archetype.setRepository( request.getRepositoryUrl() );
                }

                catalog.addArchetype( archetype );
            }

            return catalog;
        }
        finally
        {
            if ( infos != null )
            {
                infos.close();
            }
        }
    }

    public String listArchetypesAsCatalogXML( MacRequest request, IndexingContext ctx )
        throws IOException
    {
        ArchetypeCatalog catalog = listArcherypesAsCatalog( request, ctx );

        // serialize it to XML
        StringWriter sw = new StringWriter();

        writer.write( sw, catalog );

        return sw.toString();
    }

    // ==

    public static class ClassifierArtifactInfoFilter
        implements ArtifactInfoFilter
    {
        public boolean accepts( IndexingContext ctx, ArtifactInfo ai )
        {
            return StringUtils.isBlank( ai.classifier );
        }
    }
}
