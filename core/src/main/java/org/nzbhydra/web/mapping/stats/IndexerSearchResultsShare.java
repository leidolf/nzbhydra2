package org.nzbhydra.web.mapping.stats;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class IndexerSearchResultsShare {

    private String indexerName;
    private Float totalShare;
    private Float uniqueShare;

}
