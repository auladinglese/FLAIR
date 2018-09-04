package arkref.analysis;

import arkref.data.Document;
import arkref.parsestuff.U;

import java.io.IOException;


public class _SimplePipeline {

	public static void main(String[] args) throws IOException {
		for (String path : args) {
			U.pf("***  Input %s  ***\n", path);
			_SimplePipeline.go(Document.loadFiles(path));
		}
	}

	public static void go(Document d) throws IOException {
		FindMentions.go(d);
		Resolve.go(d);
		RefsToEntities.go(d);
	}

}