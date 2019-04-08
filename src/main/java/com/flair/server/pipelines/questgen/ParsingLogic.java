
package com.flair.server.pipelines.questgen;

import com.flair.server.document.AbstractDocument;
import com.flair.server.grammar.EnglishGrammaticalConstants;
import com.flair.server.parser.CoreNlpParser;
import com.flair.server.utilities.ServerLogger;
import edu.stanford.nlp.coref.CorefCoreAnnotations;
import edu.stanford.nlp.coref.data.CorefChain;
import edu.stanford.nlp.ling.CoreAnnotations;
import edu.stanford.nlp.ling.CoreLabel;
import edu.stanford.nlp.ling.tokensregex.TokenSequenceMatcher;
import edu.stanford.nlp.ling.tokensregex.TokenSequencePattern;
import edu.stanford.nlp.pipeline.Annotation;
import edu.stanford.nlp.util.CoreMap;

import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Perform NER and co-reference resolution to aid question generation.
 */
class ParsingLogic {
	static final class CorefReplacementSpan {
		int begin = -1;
		int end = -1;
		String replacement = "";

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (o == null || getClass() != o.getClass()) return false;

			CorefReplacementSpan that = (CorefReplacementSpan) o;

			if (begin != that.begin) return false;
			if (end != that.end) return false;
			return replacement.equals(that.replacement);

		}
		@Override
		public int hashCode() {
			int result = begin;
			result = 31 * result + end;
			result = 31 * result + replacement.hashCode();
			return result;
		}
	}

	private final AbstractDocument workingDoc;
	private final Set<String> mentionBlacklist;
	private final List<TokenSequencePattern> representativeMentionPatterns;

	ParsingLogic(AbstractDocument doc) {
		workingDoc = doc;
		mentionBlacklist = Stream.of(EnglishGrammaticalConstants.OBJECTIVE_PRONOUNS,
				EnglishGrammaticalConstants.SUBJECTIVE_PRONOUNS,
				EnglishGrammaticalConstants.POSSESSIVE_PRONOUNS,
				EnglishGrammaticalConstants.POSSESSIVE_ABSOLUTE_PRONOUNS,
				EnglishGrammaticalConstants.RELATIVE_PRONOUNS,
				EnglishGrammaticalConstants.REFLEXIVE_PRONOUNS,
				EnglishGrammaticalConstants.DEMONSTRATIVES,
				EnglishGrammaticalConstants.ARTICLES).flatMap(Set::stream).map(String::toLowerCase).collect(Collectors.toSet());

		// ordered list of patterns a representative mention
		representativeMentionPatterns = new ArrayList<>();
		// <Entity Mention>
		representativeMentionPatterns.add(TokenSequencePattern.compile("^([!{ner:O}]+)$"));
		// [DT] NN* <Entity Mention>
		representativeMentionPatterns.add(TokenSequencePattern.compile("[{tag:DT}]? [{tag:/NN.?/}]*? ([!{ner:O}]+)"));
		// <Entity Mention> CC <Entity Mention>
		representativeMentionPatterns.add(TokenSequencePattern.compile("([!{ner:O}]+ [{tag:/CC/}]{1} [!{ner:O}]{1,})"));
		// <Entity Mention>, ...
		representativeMentionPatterns.add(TokenSequencePattern.compile("([!{ner:O}]+ /,/+ [!{ner:O}]+ /,/? [{tag:/CC/}]{1} [!{ner:O}]+)"));
		// <Entity Mention>, Relative clause
		representativeMentionPatterns.add(TokenSequencePattern.compile("([!{ner:O}]+) /,/ [{tag:/PRP\\$?|WP\\$?/}]"));
		// *, <Entity Mention>
		representativeMentionPatterns.add(TokenSequencePattern.compile("[]* /,/ ([!{ner:O}]+)$"));
		// *, <Entity Mention>, *
		representativeMentionPatterns.add(TokenSequencePattern.compile("/,/ ([!{ner:O}]+) /,/"));
		// * <Entity Mention>
		representativeMentionPatterns.add(TokenSequencePattern.compile("[]*? ([!{ner:O}]+)$"));
		// DT [JJ*] NN* [VB*] NN*
		representativeMentionPatterns.add(TokenSequencePattern.compile("[{tag:DT}] [{tag:/JJ.?/}]* ([{tag:/NN.?/}]+ [{tag:/VB.?/}]? [{tag:/NN.?/}]+)"));
		// DT NN*
		representativeMentionPatterns.add(TokenSequencePattern.compile("^([{tag:DT}] [{tag:/NN.?|POS/}]+)"));
		// NN*
		representativeMentionPatterns.add(TokenSequencePattern.compile("^([{tag:/NN.?|POS/}]+) [!{tag:/NN.?/}]"));
	}

	private String extractRepresentativeMention(CorefChain.CorefMention mention, List<CoreMap> sentences) {
		if (mentionBlacklist.contains(mention.mentionSpan.toLowerCase()))
			return "";

		CoreMap sourceSentence = sentences.get(mention.sentNum - 1);
		List<CoreLabel> tokens = sourceSentence.get(CoreAnnotations.TokensAnnotation.class);
		List<CoreLabel> mentionTokens = tokens.subList(mention.startIndex - 1, mention.endIndex - 1)
				.stream()
				.filter(e -> !e.word().equals("``") && !e.word().equals("''"))
				.collect(Collectors.toList());
		StringBuilder sb = new StringBuilder();

		ServerLogger.get().trace("Extracting subject from mention: " + mentionTokens.stream().map(CoreLabel::word).collect(Collectors.joining(" "))).indent()
				.trace("POS: " + mentionTokens.stream().map(e -> e.get(CoreAnnotations.PartOfSpeechAnnotation.class)).collect(Collectors.joining(" ")))
				.trace("NER: " + mentionTokens.stream().map(e -> e.get(CoreAnnotations.NamedEntityTagAnnotation.class)).collect(Collectors.joining(" ")));

		boolean foundMatch = false;
		String matchedPatternString = "";
		List<CoreMap> matchedNodes = new ArrayList<>();

		for (TokenSequencePattern pattern : representativeMentionPatterns) {
			TokenSequenceMatcher matcher = pattern.getMatcher(mentionTokens);
			int matches = 0;
			while (matcher.find()) {
				if (matches >= 1) {
					ServerLogger.get().warn("Pattern '" + pattern.pattern() + "' has multiple matches. Next match: " + matcher.group(1));
					continue;
				}

				++matches;
				sb.append(matcher.group(1));
				matchedPatternString = pattern.pattern();
				matchedNodes = matcher.groupNodes(1);
			}

			if (matches > 0) {
				foundMatch = true;
				break;
			}
		}

		if ((foundMatch && matchedNodes.size() > Constants.COREF_MAX_REPRESENTATIVE_MENTION_TOKEN_COUNT) ||
				(!foundMatch && mentionTokens.size() > Constants.COREF_MAX_REPRESENTATIVE_MENTION_TOKEN_COUNT)) {
			ServerLogger.get().warn("Mention is too long!").exdent();
			return "";
		}

		if (!foundMatch) {
			for (CoreLabel token : mentionTokens) {
				if (token.word().equals(",") || EnglishGrammaticalConstants.CONTRACTIONS.contains(token.word().toLowerCase()))
					if (sb.length() > 1)
						sb.deleteCharAt(sb.length() - 1);

				sb.append(token.word()).append(" ");
			}
		}

		String extractedSubject = sb.toString().trim();
		if (!extractedSubject.isEmpty())
			extractedSubject = Character.toUpperCase(extractedSubject.charAt(0)) + extractedSubject.substring(1);

		if (!extractedSubject.isEmpty())
			ServerLogger.get().trace("Representative mention: " + extractedSubject);
		if (!matchedPatternString.isEmpty())
			ServerLogger.get().trace("Pattern: " + matchedPatternString);

		ServerLogger.get().exdent();

		return extractedSubject;
	}

	private void collateCorefReplacements(CorefChain.CorefMention nonRepresentativeMention, CorefChain.CorefMention representativeMention,
	                                      String replacement, List<CoreMap> sentences,
	                                      Map<CoreMap, Map<CorefChain.CorefMention, List<CorefReplacementSpan>>> sentencesToResolve) {
		CoreMap corefSentence = sentences.get(nonRepresentativeMention.sentNum - 1);
		List<CorefReplacementSpan> replacementData = sentencesToResolve.computeIfAbsent(corefSentence, e -> new HashMap<>())
				.computeIfAbsent(representativeMention, e -> new ArrayList<>());

		if (Constants.COREF_ONLY_REPLACE_FIRST_MENTION_IN_SENTENCE && replacementData.size() > 0) {
			ServerLogger.get().trace("Mention '" + replacement + "' has already been replaced in sentence " + nonRepresentativeMention.sentNum);
			return;
		}

		List<CoreLabel> corefSentenceTokens = corefSentence.get(CoreAnnotations.TokensAnnotation.class);
		int sentBegin = corefSentence.get(CoreAnnotations.CharacterOffsetBeginAnnotation.class);

		CorefReplacementSpan span = new CorefReplacementSpan();
		span.replacement = replacement;

		if (nonRepresentativeMention.startIndex == nonRepresentativeMention.endIndex - 1) {
			CoreLabel token = corefSentenceTokens.get(nonRepresentativeMention.startIndex - 1);
			span.begin = token.beginPosition() - sentBegin;
			span.end = token.endPosition() - sentBegin;
		} else {
			CoreLabel firstToken = corefSentenceTokens.get(nonRepresentativeMention.startIndex - 1), lastToken = corefSentenceTokens.get(nonRepresentativeMention.endIndex - 2);
			span.begin = firstToken.beginPosition() - sentBegin;
			span.end = lastToken.endPosition() - sentBegin;
		}

		if (span.begin == -1 || span.end == -1)
			ServerLogger.get().warn("Coref replacement span for mention '" + nonRepresentativeMention.toString() + "' (" + corefSentence.toString() + ") invalid");
		else
			replacementData.add(span);
	}

	private Map<CoreMap, String> reconstructCorefSentences(Map<CoreMap, Map<CorefChain.CorefMention, List<CorefReplacementSpan>>> sentencesToResolve) {
		Map<CoreMap, List<CorefReplacementSpan>> flattened = sentencesToResolve.entrySet().stream()
				.collect(Collectors.toMap(Entry::getKey, e -> e.getValue().values().stream().flatMap(Collection::stream).collect(Collectors.toList())));

		return flattened.entrySet().stream().collect(Collectors.toMap(Entry::getKey, e -> {
			// consolidate and sort spans
			List<CorefReplacementSpan> uniqueSpans = e.getValue().stream().distinct().sorted((a, b) -> {
				int result = Integer.compareUnsigned(a.begin, b.begin);
				if (result == 0)
					result = Integer.compareUnsigned(a.end, b.end);

				return result;
			}).collect(Collectors.toList());
			List<CorefReplacementSpan> delinquentSpans = new ArrayList<>();

			CorefReplacementSpan previous = null;
			for (CorefReplacementSpan current : uniqueSpans) {
				if (previous != null && current.begin < previous.end)
					delinquentSpans.add(current);
				else
					previous = current;
			}

			if (!delinquentSpans.isEmpty()) {
				ServerLogger.get().trace("Removed " + delinquentSpans.size() + " coref replacement spans for sentence '" + e.getKey().toString() + "'");
				for (CorefReplacementSpan span : delinquentSpans)
					uniqueSpans.remove(span);
			}

			// reconstruct the sentence with the corefs replaced
			String sentText = e.getKey().get(CoreAnnotations.TextAnnotation.class);
			StringBuilder resolvedSent = new StringBuilder();
			int lastOffset = 0;
			for (CorefReplacementSpan currentSpan : uniqueSpans) {
				resolvedSent.append(sentText, lastOffset, currentSpan.begin).append(currentSpan.replacement);
				lastOffset = currentSpan.end;
			}
			if (lastOffset < sentText.length())
				resolvedSent.append(sentText.substring(lastOffset));

			return resolvedSent.toString();
		}));
	}

	private void updateOriginalAnnotations(Map<CoreMap, String> resolvedSentences, CoreNlpParser parser) {
		for (Entry<CoreMap, String> sentPair : resolvedSentences.entrySet()) {
			CoreMap oldSentAnnotation = sentPair.getKey();
			String resolvedText = sentPair.getValue();

			NerCorefAnnotation nerCorefAnnotation = new NerCorefAnnotation(oldSentAnnotation, resolvedText, text -> {
				Annotation wrapper = new Annotation(text);
				parser.pipeline().annotate(wrapper);
				return wrapper;
			});

			oldSentAnnotation.set(NerCorefAnnotation.class, nerCorefAnnotation);
		}
	}

	public void apply(CoreNlpParser parser) {
		Annotation docAnnotation = new Annotation(workingDoc.getText());
		parser.pipeline().annotate(docAnnotation);

		List<CoreMap> sentences = docAnnotation.get(CoreAnnotations.SentencesAnnotation.class);
		Map<Integer, CorefChain> corefs = docAnnotation.get(CorefCoreAnnotations.CorefChainAnnotation.class)
				.entrySet().stream().filter((e) -> e.getValue().getMentionsInTextualOrder().size() > 1)
				.collect(Collectors.toMap(Entry::getKey, Entry::getValue));
		Map<CoreMap, Map<CorefChain.CorefMention, List<CorefReplacementSpan>>> modifiedSentences = new HashMap<>();     // sent -> mention -> replacements

		ServerLogger.get().trace("Coref chains = " + corefs.size());
		for (CorefChain coref : corefs.values()) {
			CorefChain.CorefMention mostRepresentative = coref.getRepresentativeMention();
			String mostRepresentativeString = extractRepresentativeMention(mostRepresentative, sentences).trim()
					.replaceAll("(^((\\p{Punct}|\\s)*\\b))|(\\b(\\p{Punct}|\\s)*$)", "");   // leading and trailing punctuation/whitespace

			if (mostRepresentativeString.isEmpty())
				continue;
			else if (mentionBlacklist.contains(mostRepresentativeString.toLowerCase()))
				continue;

			List<CorefChain.CorefMention> corefMentions = coref.getMentionsInTextualOrder();
			ServerLogger.get().trace("Secondary mentions: " + corefMentions.size());
			for (CorefChain.CorefMention mention : corefMentions) {
				if (mention != mostRepresentative)
					collateCorefReplacements(mention, mostRepresentative, mostRepresentativeString, sentences, modifiedSentences);
			}
		}

		ServerLogger.get().trace("Resolving mentions...");
		Map<CoreMap, String> resolvedSents = reconstructCorefSentences(modifiedSentences);
		ServerLogger.get().trace("Updating annotations...");
		updateOriginalAnnotations(resolvedSents, parser);

		workingDoc.flagAsParsed(parser.createAnnotations(docAnnotation));
	}
}