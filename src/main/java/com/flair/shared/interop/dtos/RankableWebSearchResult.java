package com.flair.shared.interop.dtos;

/*
 * Represents a web search result that is rankable
 */
public interface RankableWebSearchResult extends DocumentDTO {
	int getRank();        // rank in the search results

	String getUrl();
	String getDisplayUrl();
}
