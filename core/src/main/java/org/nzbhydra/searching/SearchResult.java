package org.nzbhydra.searching;

import com.google.common.collect.HashMultiset;
import com.google.common.collect.Multiset;
import lombok.Getter;
import lombok.Setter;
import org.nzbhydra.indexers.Indexer;
import org.nzbhydra.searching.IndexerForSearchSelector.IndexerForSearchSelection;

import java.util.List;

@Getter
@Setter
//@Builder
public class SearchResult {

    private List<SearchResultItem> searchResultItems;
    private List<IndexerSearchResult> indexerSearchResults;
    //private DuplicateDetectionResult duplicateDetectionResult;
    private int offset;
    private int limit;
    private Multiset<String> reasonsForRejection = HashMultiset.create();
    private IndexerForSearchSelection pickingResult;
    private Multiset<Indexer> uniqueResultsPerIndexer;

    private int numberOfTotalAvailableResults;


    public int getNumberOfProcessedResults() {
        return getNumberOfRejectedResults() + getNumberOfAcceptedResults();
    }

    public int getNumberOfAcceptedResults() {
        return searchResultItems.size();
    }

    public int getNumberOfRejectedResults() {
        return reasonsForRejection.entrySet().stream().mapToInt(Multiset.Entry::getCount).sum();
    }


}
