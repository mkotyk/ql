package com.semmle.js.extractor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import com.semmle.js.extractor.trapcache.CachingTrapWriter;
import com.semmle.js.extractor.trapcache.ITrapCache;
import com.semmle.util.data.StringUtil;
import com.semmle.util.exception.Exceptions;
import com.semmle.util.extraction.ExtractorOutputConfig;
import com.semmle.util.files.FileUtil;
import com.semmle.util.io.WholeIO;
import com.semmle.util.trap.TrapWriter;
import com.semmle.util.trap.TrapWriter.Label;

/**
 * The file extractor extracts a single file and handles source archive population and TRAP caching;
 * it delegates to the appropriate {@link IExtractor} for extracting the contents of the file.
 */
public class FileExtractor {
	/**
	 * Pattern to use on the shebang line of a script to identify whether it is a Node.js script.
	 *
	 * There are many different ways of invoking the Node.js interpreter (directly, through
	 * {@code env}, with or without flags, with or without modified environment, etc.), so we
	 * simply look for the word {@code "node"} or {@code "nodejs"}.
	 */
	private static final Pattern NODE_INVOCATION = Pattern.compile("\\bnode(js)?\\b");

	/**
	 * A pattern that matches strings starting with `{ "...":`, suggesting JSON data.
	 */
	public static final Pattern JSON_OBJECT_START = Pattern.compile("^(?s)\\s*\\{\\s*\"([^\"]|\\\\.)*\"\\s*:.*");

	/**
	 * The charset for decoding UTF-8 strings.
	 */
	private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");

	/**
	 * Information about supported file types.
	 */
	public static enum FileType {
		HTML(".htm", ".html", ".xhtm", ".xhtml", ".vue") {
			@Override
			public IExtractor mkExtractor(ExtractorConfig config, ExtractorState state) {
				return new HTMLExtractor(config);
			}

			@Override
			public String toString() {
				return "html";
			}
		},

		JS(".js", ".jsx", ".mjs", ".es6", ".es") {
			@Override
			public IExtractor mkExtractor(ExtractorConfig config, ExtractorState state) {
				return new ScriptExtractor(config);
			}

			@Override
			protected boolean contains(File f, String lcExt, ExtractorConfig config) {
				if (super.contains(f, lcExt, config))
					return true;

				// detect Node.js scripts that are meant to be run from
				// the command line and do not have a `.js` extension
				if (f.isFile() && lcExt.isEmpty()) {
					try (BufferedReader br = new BufferedReader(new FileReader(f))) {
						String firstLine = br.readLine();
						// do a cheap check first
						if (firstLine != null && firstLine.startsWith("#!")) {
							// now do the slightly more expensive one
							return NODE_INVOCATION.matcher(firstLine).find();
						}
					} catch (IOException e) {
						Exceptions.ignore(e, "We simply skip this file.");
					}
				}

				return false;
			}

			@Override
			public String toString() {
				return "javascript";
			}
		},

		JSON(".json") {
			@Override
			public IExtractor mkExtractor(ExtractorConfig config, ExtractorState state) {
				return new JSONExtractor(config);
			}

			@Override
			protected boolean contains(File f, String lcExt, ExtractorConfig config) {
				if (super.contains(f, lcExt, config))
					return true;

				// detect JSON-encoded configuration files whose name starts with `.` and ends with `rc`
				// (e.g., `.eslintrc` or `.babelrc`)
				if (f.isFile() && f.getName().matches("\\..*rc")) {
					try (BufferedReader br = new BufferedReader(new FileReader(f))) {
						// check whether the first two non-empty lines look like the start of a JSON object
						// (two lines because the opening brace is usually on a line by itself)
						StringBuilder firstTwoLines = new StringBuilder();
						for (int i=0; i<2;) {
							String nextLine = br.readLine();
							if (nextLine == null)
								break;
							nextLine = nextLine.trim();
							if (!nextLine.isEmpty()) {
								firstTwoLines.append(nextLine);
								++i;
							}
						}
						return JSON_OBJECT_START.matcher(firstTwoLines).matches();
					} catch (IOException e) {
						Exceptions.ignore(e, "We simply skip this file.");
					}
				}

				return false;
			}

			@Override
			public String toString() {
				return "json";
			}
		},

		TYPESCRIPT(".ts", ".tsx") {
			@Override
			protected boolean contains(File f, String lcExt, ExtractorConfig config) {
				if (config.getTypeScriptMode() == TypeScriptMode.NONE)
					return false;

				// Read the beginning of the file to guess the file type.
				if (hasBadFileHeader(f, lcExt, config)) {
					return false;
				}

				return super.contains(f, lcExt, config);
			}

			/**
			 * Number of bytes to read from the beginning of a ".ts" file for sniffing its
			 * file type.
			 */
			private static final int fileHeaderSize = 128;

			private boolean hasBadFileHeader(File f, String lcExt, ExtractorConfig config) {
				if (!".ts".equals(lcExt)) {
					return false;
				}
				try (FileInputStream fis = new FileInputStream(f)) {
					byte[] bytes = new byte[fileHeaderSize];
					int length = fis.read(bytes);

					// Avoid invalid or unprintable UTF-8 files.
					if (config.getDefaultEncoding().equals("UTF-8") && hasUnprintableUtf8(bytes, length)) {
						return true;
					}

					// Avoid trying to extract XML files.
					if (isXml(bytes, length))
						return true;

					// Avoid files with an unrecognized shebang header.
					if (hasUnrecognizedShebang(bytes, length)) {
						return true;
					}

					return false;
				} catch (IOException e) {
					Exceptions.ignore(e, "Let extractor handle this one.");
				}
				return false;
			}

			/** Returns the index after the initial BOM, if any, otherwise 0. */
			private int skipBOM(byte[] bytes, int length) {
				if (length >= 2 && (bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff || bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe)) {
					return 2;
				} else {
					return 0;
				}
			}

			private boolean isXml(byte[] bytes, int length) {
				int startIndex = skipBOM(bytes, length);
				// Check for `<` encoded in Ascii/UTF-8 or litte-endian UTF-16.
				if (startIndex < length && bytes[startIndex] == '<') {
					return true;
				}
				// Check for `<` encoded in big-endian UTF-16
				if (startIndex + 1 < length && bytes[startIndex] == 0 && bytes[startIndex + 1] == '<') {
					return true;
				}
				return false;
			}

			/**
			 * Returns true if the byte sequence contains invalid UTF-8 or unprintable ASCII
			 * characters.
			 */
			private boolean hasUnprintableUtf8(byte[] bytes, int length) {
				// Constants for bytes with N high-order 1-bits.
				// They are typed as `int` as the subsequent byte-to-int promotion would
				// otherwise fill the high-order `int` bits with 1s.
				final int high1 = 0b10000000;
				final int high2 = 0b11000000;
				final int high3 = 0b11100000;
				final int high4 = 0b11110000;
				final int high5 = 0b11111000;

				int startIndex = skipBOM(bytes, length);
				for (int i = startIndex; i < length; ++i) {
					int b = bytes[i];
					if ((b & high1) == 0) { // 0xxxxxxx is an ASCII character
						// ASCII values 0-31 are unprintable, except 9-13 are whitespace.
						// 127 is the unprintable DEL character.
						if (b <= 8 || 14 <= b && b <= 31 || b == 127) {
							return true;
						}
					} else {
						// Check for malformed UTF-8 multibyte code point
						int trailingBytes = 0;
						if ((b & high3) == high2) {
							trailingBytes = 1; // 110xxxxx 10xxxxxx
						} else if ((b & high4) == high3) {
							trailingBytes = 2; // 1110xxxx 10xxxxxx 10xxxxxx
						} else if ((b & high5) == high4) {
							trailingBytes = 3; // 11110xxx 10xxxxxx 10xxxxxx 10xxxxxx
						} else {
							return true; // 10xxxxxx and 11111xxx are not valid here.
						}
						// Trailing bytes must be of form 10xxxxxx
						while (trailingBytes > 0) {
							++i;
							--trailingBytes;
							if (i >= length) {
								return false;
							}
							if ((bytes[i] & high2) != high1) {
								return true;
							}
						}
					}
				}
				return false;
			}

			/**
			 * Returns true if the byte sequence starts with a shebang line that is not
			 * recognized as a JavaScript interpreter.
			 */
			private boolean hasUnrecognizedShebang(byte[] bytes, int length) {
				// Shebangs preceded by a BOM aren't recognized in UNIX, but the BOM might only
				// be present in the source file, to be stripped out in the build process.
				int startIndex = skipBOM(bytes, length);
				if (startIndex + 2 >= length) return false;
				if (bytes[startIndex] != '#' || bytes[startIndex + 1] != '!') {
					return false;
				}
				int endOfLine = -1;
				for (int i = startIndex; i < length; ++i) {
					if (bytes[i] == '\r' || bytes[i] == '\n') {
						endOfLine = i;
						break;
					}
				}
				if (endOfLine == -1) {
					// The shebang is either very long or there are no other lines in the file.
					// Treat this as unrecognized.
					return true;
				}
				// Extract the shebang text
				int startOfText = startIndex + "#!".length();
				int lengthOfText = endOfLine - startOfText;
				String text = new String(bytes, startOfText, lengthOfText, UTF8_CHARSET);
				// Check if the shebang is a recognized JavaScript intepreter.
				return !NODE_INVOCATION.matcher(text).find();
			}

			@Override
			public IExtractor mkExtractor(ExtractorConfig config, ExtractorState state) {
				return new TypeScriptExtractor(config, state.getTypeScriptParser());
			}

			@Override
			public String toString() {
				return "typescript";
			}

			@Override
			public boolean isTrapCachingAllowed() {
				return false; // Type information cannot be cached per-file.
			}
		},

		YAML(".raml", ".yaml", ".yml") {
			@Override
			public IExtractor mkExtractor(ExtractorConfig config, ExtractorState state) {
				return new YAMLExtractor(config);
			}

			@Override
			public String toString() {
				return "yaml";
			}
		};

		/**
		 * The file extensions (lower-case, including leading dot) corresponding to
		 * this file type.
		 */
		private final Set<String> extensions = new LinkedHashSet<String>();

		private FileType(String... extensions) {
			for (String extension : extensions)
				this.extensions.add(extension);
		}

		public Set<String> getExtensions() {
			return extensions;
		}

		/**
		 * Construct an extractor for this file type with the appropriate configuration settings.
		 */
		public abstract IExtractor mkExtractor(ExtractorConfig config, ExtractorState state);

		/**
		 * Determine the {@link FileType} for a given file.
		 */
		public static FileType forFile(File f, ExtractorConfig config) {
			String lcExt = StringUtil.lc(FileUtil.extension(f));
			for (FileType tp : values())
				if (tp.contains(f, lcExt, config))
					return tp;
			return null;
		}

		/**
		 * Determine the {@link FileType} for a given file based on its extension only.
		 */
		public static FileType forFileExtension(File f) {
			String lcExt = StringUtil.lc(FileUtil.extension(f));
			for (FileType tp : values())
				if (tp.getExtensions().contains(lcExt)) {
					return tp;
				}
			return null;
		}

		/**
		 * Is the given file of this type?
		 *
		 * For convenience, the lower-case file extension is also passed as an argument.
		 */
		protected boolean contains(File f, String lcExt, ExtractorConfig config) {
			return extensions.contains(lcExt);
		}

		/**
		 * Can we cache the TRAP output of this file?
		 *
		 * Caching is disabled for TypeScript files as they depend on type information
		 * from other files.
		 */
		public boolean isTrapCachingAllowed() {
			return true;
		}

		/**
		 * The names of all defined {@linkplain FileType}s.
		 */
		public static final Set<String> allNames = new LinkedHashSet<String>();
		static {
			for (FileType ft : FileType.values())
				allNames.add(ft.name());
		}
	}

	private final ExtractorConfig config;
	private final ExtractorOutputConfig outputConfig;
	private final ITrapCache trapCache;
	private final ExtractorState state;

	public FileExtractor(ExtractorConfig config, ExtractorOutputConfig outputConfig, ITrapCache trapCache, ExtractorState state) {
		this.config = config;
		this.outputConfig = outputConfig;
		this.trapCache = trapCache;
		this.state = state;
	}

	public ExtractorConfig getConfig() {
		return config;
	}

	public boolean supports(File f) {
		return config.hasFileType() || FileType.forFile(f, config) != null;
	}

	public void extract(File f) throws IOException {
		// populate source archive
		String source = new WholeIO(config.getDefaultEncoding()).strictread(f);
		outputConfig.getSourceArchive().add(f, source);

		// extract language-independent bits
		TrapWriter trapwriter = outputConfig.getTrapWriterFactory().mkTrapWriter(f);
		Label fileLabel = trapwriter.populateFile(f);
		LocationManager locationManager = new LocationManager(f, trapwriter, fileLabel);
		locationManager.emitFileLocation(fileLabel, 0, 0, 0, 0);

		// now extract the contents
		extractContents(f, fileLabel, source, locationManager);
	}


	/**
	 * Extract the contents of a file, potentially making use of cached information.
	 *
	 * TRAP files can be logically split into two parts: a location-dependent
	 * prelude containing all the `files`, `folders` and `containerparent` tuples, and
	 * a content-dependent main part containing all the rest, which does not depend on
	 * the source file location at all. Locations in the main part do, of course, refer
	 * to the source file's ID, but they do so via its symbolic label, which is always
	 * #10000.
	 *
	 * We only cache the content-dependent part, which makes up the bulk of the TRAP
	 * file anyway. The location-dependent part is emitted from scratch every time
	 * by the {@link #extract(File)} method above.
	 *
	 * In order to keep labels in the main part independent of the file's location,
	 * we bump the TRAP label counter to a known value (currently 20000) after the
	 * location-dependent part has been emitted. If the counter should already be larger
	 * than that (which is theoretically possible with insanely deeply nested directories),
	 * we have to skip caching.
	 *
	 * Also note that we support extraction with TRAP writer factories that are not file-backed;
	 * obviously, no caching is done in that scenario.
	 */
	private void extractContents(File f, Label fileLabel, String source, LocationManager locationManager) throws IOException {
		TrapWriter trapwriter = locationManager.getTrapWriter();
		FileType fileType = config.hasFileType() ? FileType.valueOf(config.getFileType())
				: FileType.forFile(f, config);

		File cacheFile = null,   // the cache file for this extraction
		     resultFile = null;  // the final result TRAP file for this extraction

		// check whether we can perform caching
		if (bumpIdCounter(trapwriter) && fileType.isTrapCachingAllowed()) {
			resultFile = outputConfig.getTrapWriterFactory().getTrapFileFor(f);
			if (resultFile != null)
				cacheFile = trapCache.lookup(source, config, fileType);
		}

		if (cacheFile != null) {
			FileUtil.close(trapwriter);

			if (cacheFile.exists()) {
				FileUtil.append(cacheFile, resultFile);
				return;
			}

			// not in the cache yet, so use a caching TRAP writer to
			// put the data into the cache and append it to the result file
			trapwriter = new CachingTrapWriter(cacheFile, resultFile);
			bumpIdCounter(trapwriter);
			// re-initialise the location manager, since it keeps a reference to the TRAP writer
			locationManager = new LocationManager(f, trapwriter, locationManager.getFileLabel());
		}

		// now do the extraction itself
		boolean successful = false;
		try {
			IExtractor extractor = fileType.mkExtractor(config, state);
			TextualExtractor textualExtractor = new TextualExtractor(trapwriter, locationManager,
					source, config.getExtractLines());
			LoCInfo loc = extractor.extract(textualExtractor);
			int numLines = textualExtractor.getNumLines();
			int linesOfCode = loc.getLinesOfCode(), linesOfComments = loc.getLinesOfComments();
			trapwriter.addTuple("numlines", fileLabel, numLines, linesOfCode, linesOfComments);
			trapwriter.addTuple("filetype", fileLabel, fileType.toString());
			successful = true;
		} finally {
			if (!successful && trapwriter instanceof CachingTrapWriter)
				((CachingTrapWriter) trapwriter).discard();

			FileUtil.close(trapwriter);
		}
	}

	/**
	 * Bump trap ID counter to separate path-dependent and path-independent parts of the TRAP file.
	 *
	 * @return true if the counter was successfully bumped
	 */
	public boolean bumpIdCounter(TrapWriter trapwriter) {
		return trapwriter.bumpIdCount(20000);
	}
}
