package org.springframework.shell;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.Logger;

import jline.ANSIBuffer;
import jline.ANSIBuffer.ANSICodes;
import jline.ConsoleReader;
import jline.WindowsTerminal;

import org.apache.commons.io.input.ReversedLinesFileReader;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.roo.shell.AbstractShell;
import org.springframework.roo.shell.CommandMarker;
import org.springframework.roo.shell.ExitShellRequest;
import org.springframework.roo.shell.Shell;
import org.springframework.roo.shell.event.ShellStatus;
import org.springframework.roo.shell.event.ShellStatus.Status;
import org.springframework.roo.shell.event.ShellStatusListener;
import org.springframework.roo.support.util.Assert;
import org.springframework.roo.support.util.ClassUtils;
import org.springframework.roo.support.util.IOUtils;
import org.springframework.roo.support.util.OsUtils;
import org.springframework.roo.support.util.StringUtils;
import org.springframework.shell.plugin.BannerProvider;
import org.springframework.shell.plugin.HistoryFileNameProvider;
import org.springframework.shell.plugin.PluginProvider;
import org.springframework.shell.plugin.PromptProvider;


/**
 * Uses the feature-rich <a href="http://sourceforge.net/projects/jline/">JLine</a> library to provide an interactive shell.
 *
 * <p>
 * Due to Windows' lack of color ANSI services out-of-the-box, this implementation automatically detects the classpath
 * presence of <a href="http://jansi.fusesource.org/">Jansi</a> and uses it if present. This library is not necessary
 * for *nix machines, which support colour ANSI without any special effort. This implementation has been written to
 * use reflection in order to avoid hard dependencies on Jansi.
 *
 * @author Ben Alex
 * @author Jarred Li
 * @since 1.0
 */
public abstract class JLineShell extends AbstractShell implements CommandMarker, Shell, Runnable {

	// Constants
	private static final String ANSI_CONSOLE_CLASSNAME = "org.fusesource.jansi.AnsiConsole";
	private static final boolean JANSI_AVAILABLE = ClassUtils.isPresent(ANSI_CONSOLE_CLASSNAME,
			JLineShell.class.getClassLoader());
	private static final boolean APPLE_TERMINAL = Boolean.getBoolean("is.apple.terminal");
	private static final char ESCAPE = 27;
	private static final String BEL = "\007";
	// Fields
	protected ConsoleReader reader;
	private boolean developmentMode = false;
	private FileWriter fileLog;
	private final DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
	protected ShellStatusListener statusListener; // ROO-836
	/** key: slot name, value: flashInfo instance */
	private final Map<String, FlashInfo> flashInfoMap = new HashMap<String, FlashInfo>();
	/** key: row number, value: eraseLineFromPosition */
	private final Map<Integer, Integer> rowErasureMap = new HashMap<Integer, Integer>();
	private boolean shutdownHookFired = false; // ROO-1599

	private ApplicationContext applicatonContext;
	private boolean printBanner = true;

	private static AnnotationAwareOrderComparator annocationOrderComparator = new AnnotationAwareOrderComparator();

	private String historyFileName;
	private String promptText;
	private String productName;
	private String banner;
	private String version;
	private String welcomeMessage;

	private int historySize;

	public void run() {
		reader = createConsoleReader();
	    	
		setPromptPath(null);

		JLineLogHandler handler = new JLineLogHandler(reader, this);
		JLineLogHandler.prohibitRedraw(); // Affects this thread only
		Logger mainLogger = Logger.getLogger("");
		removeHandlers(mainLogger);
		mainLogger.addHandler(handler);

		reader.addCompletor(new JLineCompletorAdapter(getParser()));

		reader.setBellEnabled(true);
		if (Boolean.getBoolean("jline.nobell")) {
			reader.setBellEnabled(false);
		}

		// reader.setDebug(new PrintWriter(new FileWriter("writer.debug", true)));

		openFileLogIfPossible();
		this.reader.getHistory().setMaxSize(this.historySize);
		// Try to build previous command history from the project's log
		String[] filteredLogEntries = filterLogEntry();
		for (String logEntry : filteredLogEntries) {
			reader.getHistory().addToHistory(logEntry);
		}

		flashMessageRenderer();
		flash(Level.FINE, this.productName + " " + this.version, Shell.WINDOW_TITLE_SLOT);
		printBannerAndWelcome();

		String startupNotifications = getStartupNotifications();
		if (StringUtils.hasText(startupNotifications)) {
			logger.info(startupNotifications);
		}

		setShellStatus(Status.STARTED);

		// Monitor CTRL+C initiated shutdowns (ROO-1599)
		Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
			public void run() {
				shutdownHookFired = true;
				// We don't need to closeShell(), as the shutdown hook in o.s.r.bootstrap.Main calls stop() which calls JLineShellComponent.deactivate() and that calls closeShell()
			}
		}, "Spring Roo JLine Shutdown Hook"));

		// Handle any "execute-then-quit" operation

		String rooArgs = System.getProperty("roo.args");
		if (rooArgs != null && !"".equals(rooArgs)) {
			setShellStatus(Status.USER_INPUT);
			boolean success = executeCommand(rooArgs);
			if (exitShellRequest == null) {
				// The command itself did not specify an exit shell code, so we'll fall back to something sensible here
				executeCommand("quit"); // ROO-839
				exitShellRequest = success ? ExitShellRequest.NORMAL_EXIT : ExitShellRequest.FATAL_EXIT;
			}
			setShellStatus(Status.SHUTTING_DOWN);
		}
		else {
			// Normal RPEL processing
			promptLoop();
		}

	}

	/**
	 * read history commands from history log. the history size if determined by --histsize options. 
	 * 
	 * @return history commands
	 */
	private String[] filterLogEntry() {
		ArrayList<String> entries = new ArrayList<String>();		
		try {
			ReversedLinesFileReader reader = new ReversedLinesFileReader(
					new File(this.historyFileName),4096,Charset.forName("UTF-8"));
			int size = 0;
			String line = null;
			while ((line = reader.readLine()) != null) {
				if (!line.startsWith("//")) {
					size++;
					if (size > historySize) {
						break;
					}
					else {
						entries.add(line);
					}
				}
			}
		} catch (IOException e) {
			logger.warning("read history file failed. Reason:"+ e.getMessage());
		}
		Collections.reverse(entries);
		return entries.toArray(new String[0]);
	}
	
	/**
	 * Creates new jline ConsoleReader. On Windows if jansi is available, uses 
	 * createAnsiWindowsReader(). Otherwise, always creates a default ConsoleReader.
	 * Sub-classes of this class can plug in their version of ConsoleReader 
	 * by overriding this method, if required.  
	 * 
	 * @return a jline ConsoleReader instance
	 */
	protected ConsoleReader createConsoleReader() {
		ConsoleReader consoleReader = null;
		try {
			if (JANSI_AVAILABLE && OsUtils.isWindows()) {
				try {
				    consoleReader = createAnsiWindowsReader();
				} catch (Exception e) {
					// Try again using default ConsoleReader constructor
					logger.warning("Can't initialize jansi AnsiConsole, falling back to default: " + e);
				}
			}
			if (consoleReader == null) {
			    consoleReader = new ConsoleReader();
			}
		} catch (IOException ioe) {
			throw new IllegalStateException("Cannot start console class", ioe);
		}
		return consoleReader;
	}

	public void printBannerAndWelcome() {
		if (printBanner) {
			logger.info(this.banner);
			logger.info(getWelcomeMessage());
		}
	}

	public String getStartupNotifications() {
		return null;
	}

	private void removeHandlers(final Logger l) {
		Handler[] handlers = l.getHandlers();
		if (handlers != null && handlers.length > 0) {
			for (Handler h : handlers) {
				l.removeHandler(h);
			}
		}
	}

	@Override
	public void setPromptPath(final String path) {
		setPromptPath(path, false);
	}

	@Override
	public void setPromptPath(final String path, final boolean overrideStyle) {
		if (reader.getTerminal().isANSISupported()) {
			ANSIBuffer ansi = JLineLogHandler.getANSIBuffer();
			if (path == null || "".equals(path)) {
				shellPrompt = ansi.yellow(this.promptText).toString();
			}
			else {
				if (overrideStyle) {
					ansi.append(path);
				}
				else {
					ansi.cyan(path);
				}
				shellPrompt = ansi.yellow(" " + this.promptText).toString();
			}
		}
		else {
			// The superclass will do for this non-ANSI terminal
			super.setPromptPath(path);
		}

		// The shellPrompt is now correct; let's ensure it now gets used
		reader.setDefaultPrompt(JLineShell.shellPrompt);
	}

	protected ConsoleReader createAnsiWindowsReader() throws Exception {
		// Get decorated OutputStream that parses ANSI-codes
		final PrintStream ansiOut = (PrintStream) ClassUtils.forName(ANSI_CONSOLE_CLASSNAME,
				JLineShell.class.getClassLoader()).getMethod("out").invoke(null);
		WindowsTerminal ansiTerminal = new WindowsTerminal() {
			@Override
			public boolean isANSISupported() {
				return true;
			}
		};
		ansiTerminal.initializeTerminal();
		// Make sure to reset the original shell's colors on shutdown by closing the stream
		statusListener = new ShellStatusListener() {
			public void onShellStatusChange(final ShellStatus oldStatus, final ShellStatus newStatus) {
				if (newStatus.getStatus().equals(Status.SHUTTING_DOWN)) {
					ansiOut.close();
				}
			}
		};
		addShellStatusListener(statusListener);

		return new ConsoleReader(new FileInputStream(FileDescriptor.in), new PrintWriter(new OutputStreamWriter(
				ansiOut,
				// Default to Cp850 encoding for Windows console output (ROO-439)
				System.getProperty("jline.WindowsTerminal.output.encoding", "Cp850"))), null, ansiTerminal);
	}

	private void flashMessageRenderer() {
		if (!reader.getTerminal().isANSISupported()) {
			return;
		}
		// Setup a thread to ensure flash messages are displayed and cleared correctly
		Thread t = new Thread(new Runnable() {
			public void run() {
				while (!shellStatus.getStatus().equals(Status.SHUTTING_DOWN) && !shutdownHookFired) {
					synchronized (flashInfoMap) {
						long now = System.currentTimeMillis();

						Set<String> toRemove = new HashSet<String>();
						for (String slot : flashInfoMap.keySet()) {
							FlashInfo flashInfo = flashInfoMap.get(slot);

							if (flashInfo.flashMessageUntil < now) {
								// Message has expired, so clear it
								toRemove.add(slot);
								doAnsiFlash(flashInfo.rowNumber, Level.ALL, "");
							}
							else {
								// The expiration time for this message has not been reached, so preserve it
								doAnsiFlash(flashInfo.rowNumber, flashInfo.flashLevel, flashInfo.flashMessage);
							}
						}
						for (String slot : toRemove) {
							flashInfoMap.remove(slot);
						}
					}
					try {
						Thread.sleep(200);
					} catch (InterruptedException ignore) {
					}
				}
			}
		}, "Spring Roo JLine Flash Message Manager");
		t.start();
	}

	@Override
	public void flash(final Level level, final String message, final String slot) {
		Assert.notNull(level, "Level is required for a flash message");
		Assert.notNull(message, "Message is required for a flash message");
		Assert.hasText(slot, "Slot name must be specified for a flash message");

		if (Shell.WINDOW_TITLE_SLOT.equals(slot)) {
			if (reader != null && reader.getTerminal().isANSISupported()) {
				// We can probably update the window title, as requested
				if (StringUtils.isBlank(message)) {
					System.out.println("No text");
				}

				ANSIBuffer buff = JLineLogHandler.getANSIBuffer();
				buff.append(ESCAPE + "]0;").append(message).append(BEL);
				String stg = buff.toString();
				try {
					reader.printString(stg);
					reader.flushConsole();
				} catch (IOException ignored) {
				}
			}

			return;
		}
		if ((reader != null && !reader.getTerminal().isANSISupported())) {
			super.flash(level, message, slot);
			return;
		}
		synchronized (flashInfoMap) {
			FlashInfo flashInfo = flashInfoMap.get(slot);

			if ("".equals(message)) {
				// Request to clear the message, but give the user some time to read it first
				if (flashInfo == null) {
					// We didn't have a record of displaying it in the first place, so just quit
					return;
				}
				flashInfo.flashMessageUntil = System.currentTimeMillis() + 1500;
			}
			else {
				// Display this message displayed until further notice
				if (flashInfo == null) {
					// Find a row for this new slot; we basically take the first line number we discover
					flashInfo = new FlashInfo();
					flashInfo.rowNumber = Integer.MAX_VALUE;
					outer: for (int i = 1; i < Integer.MAX_VALUE; i++) {
						for (FlashInfo existingFlashInfo : flashInfoMap.values()) {
							if (existingFlashInfo.rowNumber == i) {
								// Veto, let's try the new candidate row number
								continue outer;
							}
						}
						// If we got to here, nobody owns this row number, so use it
						flashInfo.rowNumber = i;
						break outer;
					}

					// Store it
					flashInfoMap.put(slot, flashInfo);
				}
				// Populate the instance with the latest data
				flashInfo.flashMessageUntil = Long.MAX_VALUE;
				flashInfo.flashLevel = level;
				flashInfo.flashMessage = message;

				// Display right now
				doAnsiFlash(flashInfo.rowNumber, flashInfo.flashLevel, flashInfo.flashMessage);
			}
		}
	}

	// Externally synchronized via the two calling methods having a mutex on flashInfoMap
	private void doAnsiFlash(final int row, final Level level, final String message) {
		ANSIBuffer buff = JLineLogHandler.getANSIBuffer();
		if (APPLE_TERMINAL) {
			buff.append(ESCAPE + "7");
		}
		else {
			buff.append(ANSICodes.save());
		}

		// Figure out the longest line we're presently displaying (or were) and erase the line from that position
		int mostFurtherLeftColNumber = Integer.MAX_VALUE;
		for (Integer candidate : rowErasureMap.values()) {
			if (candidate < mostFurtherLeftColNumber) {
				mostFurtherLeftColNumber = candidate;
			}
		}

		if (mostFurtherLeftColNumber == Integer.MAX_VALUE) {
			// There is nothing to erase
		}
		else {
			buff.append(ANSICodes.gotoxy(row, mostFurtherLeftColNumber));
			buff.append(ANSICodes.clreol()); // Clear what was present on the line
		}

		if (("".equals(message))) {
			// They want the line blank; we've already achieved this if needed via the erasing above
			// Just need to record we no longer care about this line the next time doAnsiFlash is invoked
			rowErasureMap.remove(row);
		}
		else {
			if (shutdownHookFired) {
				return; // ROO-1599
			}
			// They want some message displayed
			int startFrom = reader.getTermwidth() - message.length() + 1;
			if (startFrom < 1) {
				startFrom = 1;
			}
			buff.append(ANSICodes.gotoxy(row, startFrom));
			buff.reverse(message);
			// Record we want to erase from this positioning next time (so we clean up after ourselves)
			rowErasureMap.put(row, startFrom);
		}
		if (APPLE_TERMINAL) {
			buff.append(ESCAPE + "8");
		}
		else {
			buff.append(ANSICodes.restore());
		}

		String stg = buff.toString();
		try {
			reader.printString(stg);
			reader.flushConsole();
		} catch (IOException ignored) {
		}
	}

	public void promptLoop() {
		setShellStatus(Status.USER_INPUT);
		String line;

		try {
			while (exitShellRequest == null && ((line = reader.readLine()) != null)) {
				JLineLogHandler.resetMessageTracking();
				setShellStatus(Status.USER_INPUT);

				if ("".equals(line)) {
					continue;
				}

				executeCommand(line);
				//System.out.println("executed command:" + line);
			}
		} catch (IOException ioe) {
			throw new IllegalStateException("Shell line reading failure", ioe);
		}
		System.out.println("shutting down ...");
		setShellStatus(Status.SHUTTING_DOWN);
	}

	public void setDevelopmentMode(final boolean developmentMode) {
		JLineLogHandler.setIncludeThreadName(developmentMode);
		JLineLogHandler.setSuppressDuplicateMessages(!developmentMode); // We want to see duplicate messages during development time (ROO-1873)
		this.developmentMode = developmentMode;
	}

	public boolean isDevelopmentMode() {
		return this.developmentMode;
	}

	private void openFileLogIfPossible() {
		try {
			fileLog = new FileWriter(this.historyFileName, true);
			// First write, so let's record the date and time of the first user command
			fileLog.write("// Spring Roo " + versionInfo() + " log opened at " + df.format(new Date()) + "\n");
			fileLog.flush();
		} catch (IOException ignoreIt) {
		}
	}



	@Override
	protected void logCommandToOutput(final String processedLine) {
		if (fileLog == null) {
			openFileLogIfPossible();
			if (fileLog == null) {
				// Still failing, so give up
				return;
			}
		}
		try {
			fileLog.write(processedLine + "\n"); // Unix line endings only from Roo
			fileLog.flush(); // So tail -f will show it's working
			if (getExitShellRequest() != null) {
				// Shutting down, so close our file (we can always reopen it later if needed)
				fileLog.write("// Spring Roo " + versionInfo() + " log closed at " + df.format(new Date()) + "\n");
				IOUtils.closeQuietly(fileLog);
				fileLog = null;
			}
		} catch (IOException ignoreIt) {
		}
	}

	/**
	 * Obtains the "roo.home" from the system property, falling back to the current working directory if missing.
	 *
	 * @return the 'roo.home' system property
	 */
	@Override
	protected String getHomeAsString() {
		String rooHome = System.getProperty("roo.home");
		if (rooHome == null) {
			try {
				rooHome = new File(".").getCanonicalPath();
			} catch (Exception e) {
				throw new IllegalStateException(e);
			}
		}
		return rooHome;
	}

	/**
	 * Should be called by a subclass before deactivating the shell.
	 */
	protected void closeShell() {
		// Notify we're closing down (normally our status is already shutting_down, but if it was a CTRL+C via the o.s.r.bootstrap.Main hook)
		setShellStatus(Status.SHUTTING_DOWN);
		if (statusListener != null) {
			removeShellStatusListener(statusListener);
		}
	}

	private static class FlashInfo {
		String flashMessage;
		long flashMessageUntil;
		Level flashLevel;
		int rowNumber;
	}

	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.applicatonContext = applicationContext;
	}

	public void costomizePlugin() {
		this.historyFileName = getHistoryFileName();
		this.promptText = getPromptText();
		String[] banner = getBannerText();
		this.banner = banner[0];
		this.welcomeMessage = banner[1];
		this.version = banner[2];
		this.productName = banner[3];
	}

	/**
	 * get history file name from provider. The provider has highest order 
	 * <link>org.springframework.core.Ordered.getOder</link> will win. 
	 * 
	 * @return history file name 
	 */
	private String getHistoryFileName() {
		return getHighestPriorityProvider(HistoryFileNameProvider.class).getHistoryFileName();
	}

	/**
	 * get prompt text from provider. The provider has highest order 
	 * <link>org.springframework.core.Ordered.getOder</link> will win. 
	 * 
	 * @return prompt text
	 */
	private String getPromptText() {
		return getHighestPriorityProvider(PromptProvider.class).getPromptText();
	}

	/**
	 * Get Banner and Welcome Message from provider. The provider has highest order 
	 * <link>org.springframework.core.Ordered.getOder</link> will win. 
	 * @return BannerText[0]: Banner
	 *         BannerText[1]: Welcome Message
	 *         BannerText[2]: Version
	 *         BannerText[3]: Product Name
	 */
	private String[] getBannerText() {
		String[] bannerText = new String[4];
		BannerProvider provider = getHighestPriorityProvider(BannerProvider.class);
		bannerText[0] = provider.getBanner();
		bannerText[1] = provider.getWelcomeMessage();
		bannerText[2] = provider.getVersion();
		bannerText[3] = provider.name();
		return bannerText;
	}


	private <T extends PluginProvider> T getHighestPriorityProvider(Class<T> t) {
		Map<String, T> providers = BeanFactoryUtils.beansOfTypeIncludingAncestors(this.applicatonContext, t);
		List<T> sortedProviders = new ArrayList<T>(providers.values());
		Collections.sort(sortedProviders, annocationOrderComparator);
		T highestPriorityProvider = sortedProviders.get(0);
		return highestPriorityProvider;
	}


	/**
	 * get the welcome message at start.
	 * 
	 * @return welcome message
	 */
	public String getWelcomeMessage() {
		return this.welcomeMessage;
	}


	/**
	 * @param printBanner the printBanner to set
	 */
	public void setPrintBanner(boolean printBanner) {
		this.printBanner = printBanner;
	}

	/**
	 * @return the historySize
	 */
	public int getHistorySize() {
		return historySize;
	}

	/**
	 * @param historySize the historySize to set
	 */
	public void setHistorySize(int historySize) {
		this.historySize = historySize;
	}

}
