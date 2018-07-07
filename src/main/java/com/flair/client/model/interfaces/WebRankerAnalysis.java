package com.flair.client.model.interfaces;

import com.flair.client.model.interfaces.AbstractWebRankerCore.OperationType;
import com.flair.shared.grammar.Language;
import com.flair.shared.interop.RankableDocument;

import java.util.List;

/*
 * Represents a executing or completed analysis operation
 */
public interface WebRankerAnalysis {
	public OperationType getType();
	public String getName();        // web search - <query>
	// corpus - <list of file names>

	public Language getLanguage();
	public List<RankableDocument> getParsedDocs();
	public boolean inProgress();
}