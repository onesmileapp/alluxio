/*
 * The Alluxio Open Foundation licenses this work under the Apache License, version 2.0
 * (the "License"). You may not use this work except in compliance with the License, which is
 * available at www.apache.org/licenses/LICENSE-2.0
 *
 * This software is distributed on an "AS IS" basis, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied, as more fully set forth in the License.
 *
 * See the NOTICE file distributed with this work for information regarding copyright ownership.
 */

package alluxio;

import alluxio.exception.ExceptionMessage;
import alluxio.network.ChannelType;
import alluxio.util.ConfigurationUtils;
import alluxio.util.FormatUtils;
import alluxio.util.network.NetworkAddressUtils;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Throwables;
import com.google.common.collect.Lists;
import io.netty.util.internal.chmv8.ConcurrentHashMapV8;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.concurrent.NotThreadSafe;

/**
 * <p>
 * All the runtime configuration properties of Alluxio. This class works like a dictionary and
 * serves each Alluxio configuration property as a key-value pair.
 *
 * <p>
 * Alluxio configuration properties are loaded into this class in the following order with
 * decreasing priority:
 * <ol>
 * <li>Java system properties;</li>
 * <li>Environment variables via {@code alluxio-env.sh} or from OS settings;</li>
 * <li>Site specific properties via {@code alluxio-site.properties} file;</li>
 * <li>Default properties via {@code alluxio-default.properties} file.</li>
 * </ol>
 *
 * <p>
 * The default properties are defined in a property file {@code alluxio-default.properties}
 * distributed with Alluxio jar. Alluxio users can override values of these default properties by
 * creating {@code alluxio-site.properties} and putting it under java {@code CLASSPATH} when running
 * Alluxio (e.g., ${ALLUXIO_HOME}/conf/)
 */
@NotThreadSafe
public final class Configuration {
  private static final Logger LOG = LoggerFactory.getLogger(Constants.LOGGER_TYPE);

  /** File to set default properties. */
  private static final String DEFAULT_PROPERTIES = "alluxio-default.properties";
  /** File to set customized properties for Alluxio server (both master and worker) and client. */
  private static final String SITE_PROPERTIES = "alluxio-site.properties";

  /** Regex string to find "${key}" for variable substitution. */
  private static final String REGEX_STRING = "(\\$\\{([^{}]*)\\})";
  /** Regex to find ${key} for variable substitution. */
  private static final Pattern CONF_REGEX = Pattern.compile(REGEX_STRING);
  /** Map of properties. */
  private static final ConcurrentHashMapV8<String, String> PROPERTIES_MAP =
      new ConcurrentHashMapV8<>();

  static {
    defaultInit();
  }

  /**
   * The minimal configuration without loading any site or system properties.
   */
  public static void emptyInit() {
    init(null, false);
  }

  /**
   * The default configuration.
   */
  public static void defaultInit() {
    init(SITE_PROPERTIES, true);
  }

  /**
   * Constructor with a flag to indicate whether system properties should be included. When the flag
   * is set to false, it is used for {@link Configuration} test class.
   *
   * @param sitePropertiesFile site-wide properties
   * @param includeSystemProperties whether to include the system properties
   */
  private static void init(String sitePropertiesFile, boolean includeSystemProperties) {
    // Load default
    Properties defaultProps = ConfigurationUtils.loadPropertiesFromResource(DEFAULT_PROPERTIES);
    if (defaultProps == null) {
      throw new RuntimeException(
          ExceptionMessage.DEFAULT_PROPERTIES_FILE_DOES_NOT_EXIST.getMessage());
    }
    // Override runtime default
    defaultProps.setProperty(Constants.MASTER_HOSTNAME, NetworkAddressUtils.getLocalHostName(250));
    defaultProps.setProperty(Constants.WORKER_NETWORK_NETTY_CHANNEL,
        String.valueOf(ChannelType.defaultType()));
    defaultProps.setProperty(Constants.USER_NETWORK_NETTY_CHANNEL,
        String.valueOf(ChannelType.defaultType()));

    String confPaths;
    // If site conf is overwritten in system properties, overwrite the default setting
    if (System.getProperty(Constants.SITE_CONF_DIR) != null) {
      confPaths = System.getProperty(Constants.SITE_CONF_DIR);
    } else {
      confPaths = defaultProps.getProperty(Constants.SITE_CONF_DIR);
    }
    String[] confPathList = confPaths.split(",");

    // Load site specific properties file
    Properties siteProps = ConfigurationUtils
        .searchPropertiesFile(sitePropertiesFile, confPathList);

    // Load system properties
    Properties systemProps = new Properties();
    if (includeSystemProperties) {
      systemProps.putAll(System.getProperties());
    }

    // Now lets combine, order matters here
    putFromProperties(defaultProps);
    if (siteProps != null) {
      putFromProperties(siteProps);
    }
    putFromProperties(systemProps);

    String masterHostname = PROPERTIES_MAP.get(Constants.MASTER_HOSTNAME);
    String masterPort = PROPERTIES_MAP.get(Constants.MASTER_RPC_PORT);
    boolean useZk = Boolean.parseBoolean(PROPERTIES_MAP.get(Constants.ZOOKEEPER_ENABLED));
    String masterAddress =
        (useZk ? Constants.HEADER_FT : Constants.HEADER) + masterHostname + ":" + masterPort;
    PROPERTIES_MAP.put(Constants.MASTER_ADDRESS, masterAddress);
    checkUserFileBufferBytes();

    // Make sure the user hasn't set worker ports when there may be multiple workers per host
    int maxWorkersPerHost = getInt(Constants.INTEGRATION_YARN_WORKERS_PER_HOST_MAX);
    if (maxWorkersPerHost > 1) {
      String message = "%s cannot be specified when allowing multiple workers per host with "
          + Constants.INTEGRATION_YARN_WORKERS_PER_HOST_MAX + "=" + maxWorkersPerHost;
      Preconditions.checkState(System.getProperty(Constants.WORKER_DATA_PORT) == null,
          String.format(message, Constants.WORKER_DATA_PORT));
      Preconditions.checkState(System.getProperty(Constants.WORKER_RPC_PORT) == null,
          String.format(message, Constants.WORKER_RPC_PORT));
      Preconditions.checkState(System.getProperty(Constants.WORKER_WEB_PORT) == null,
          String.format(message, Constants.WORKER_WEB_PORT));
      PROPERTIES_MAP.put(Constants.WORKER_DATA_PORT, "0");
      PROPERTIES_MAP.put(Constants.WORKER_RPC_PORT, "0");
      PROPERTIES_MAP.put(Constants.WORKER_WEB_PORT, "0");
    }
  }

  private static void putFromProperties(Properties properties) {
    for (String key : properties.stringPropertyNames()) {
      PROPERTIES_MAP.put(key, properties.getProperty(key));
    }
  }

  /**
   * Merges the current configuration properties with alternate properties. A property from the new
   * configuration wins if it also appears in the current configuration.
   *
   * @param properties The source {@link Properties} to be merged
   */
  public static void merge(Map<?, ?> properties) {
    if (properties != null) {
      // merge the system properties
      for (Map.Entry<?, ?> entry : properties.entrySet()) {
        PROPERTIES_MAP.put(entry.getKey().toString(), entry.getValue().toString());
      }
    }
    checkUserFileBufferBytes();
  }

  // Public accessor methods

  // TODO(binfan): this method should be hidden and only used during initialization and tests.

  /**
   * Sets the value for the appropriate key in the {@link Properties}.
   *
   * @param key the key to set
   * @param value the value for the key
   */
  public static void set(String key, String value) {
    Preconditions.checkArgument(key != null && value != null,
        String.format("the key value pair (%s, %s) cannot have null", key, value));
    PROPERTIES_MAP.put(key, value);
    checkUserFileBufferBytes();
  }

  /**
   * Gets the value for the given key in the {@link Properties}.
   *
   * @param key the key to get the value for
   * @return the value for the given key
   */
  public static String get(String key) {
    String raw = PROPERTIES_MAP.get(key);
    if (raw == null) {
      // if key is not found among the default properties
      throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
    }
    return lookup(raw);
  }

  /**
   * Checks if the {@link Properties} contains the given key.
   *
   * @param key the key to check
   * @return true if the key is in the {@link Properties}, false otherwise
   */
  public static boolean containsKey(String key) {
    return PROPERTIES_MAP.containsKey(key);
  }

  /**
   * Gets the integer representation of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the value for the given key as an {@code int}
   */
  public static int getInt(String key) {
    if (PROPERTIES_MAP.containsKey(key)) {
      String rawValue = PROPERTIES_MAP.get(key);
      try {
        return Integer.parseInt(lookup(rawValue));
      } catch (NumberFormatException e) {
        throw new RuntimeException(ExceptionMessage.KEY_NOT_INTEGER.getMessage(key));
      }
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the long representation of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the value for the given key as a {@code long}
   */
  public static long getLong(String key) {
    if (PROPERTIES_MAP.containsKey(key)) {
      String rawValue = PROPERTIES_MAP.get(key);
      try {
        return Long.parseLong(lookup(rawValue));
      } catch (NumberFormatException e) {
        LOG.warn("Configuration cannot evaluate key {} as long.", key);
      }
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the double representation of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the value for the given key as a {@code double}
   */
  public static double getDouble(String key) {
    if (PROPERTIES_MAP.containsKey(key)) {
      String rawValue = PROPERTIES_MAP.get(key);
      try {
        return Double.parseDouble(lookup(rawValue));
      } catch (NumberFormatException e) {
        throw new RuntimeException(ExceptionMessage.KEY_NOT_DOUBLE.getMessage(key));
      }
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the float representation of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the value for the given key as a {@code float}
   */
  public static float getFloat(String key) {
    if (PROPERTIES_MAP.containsKey(key)) {
      String rawValue = PROPERTIES_MAP.get(key);
      try {
        return Float.parseFloat(lookup(rawValue));
      } catch (NumberFormatException e) {
        LOG.warn("Configuration cannot evaluate key {} as float.", key);
      }
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the boolean representation of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the value for the given key as a {@code boolean}
   */
  public static boolean getBoolean(String key) {
    if (PROPERTIES_MAP.containsKey(key)) {
      String rawValue = PROPERTIES_MAP.get(key);
      String value = lookup(rawValue);
      if (value.equalsIgnoreCase("true")) {
        return true;
      } else if (value.equalsIgnoreCase("false")) {
        return false;
      } else {
        throw new RuntimeException(ExceptionMessage.KEY_NOT_BOOLEAN.getMessage(key));
      }
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the value for the given key as a list.
   *
   * @param key the key to get the value for
   * @param delimiter the delimiter to split the values
   * @return the list of values for the given key
   */
  public static List<String> getList(String key, String delimiter) {
    Preconditions.checkArgument(delimiter != null, "Illegal separator for Alluxio properties as "
        + "list");
    if (PROPERTIES_MAP.containsKey(key)) {
      String rawValue = PROPERTIES_MAP.get(key);
      return Lists.newLinkedList(Splitter.on(delimiter).trimResults().omitEmptyStrings()
          .split(rawValue));
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the value for the given key as an enum value.
   *
   * @param key the key to get the value for
   * @param enumType the type of the enum
   * @param <T> the type of the enum
   * @return the value for the given key as an enum value
   */
  public static <T extends Enum<T>> T getEnum(String key, Class<T> enumType) {
    if (!PROPERTIES_MAP.containsKey(key)) {
      throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
    }
    final String val = get(key);
    return Enum.valueOf(enumType, val);
  }

  /**
   * Gets the bytes of the value for the given key.
   *
   * @param key the key to get the value for
   * @return the bytes of the value for the given key
   */
  public static long getBytes(String key) {
    if (PROPERTIES_MAP.containsKey(key)) {
      String rawValue = get(key);
      try {
        return FormatUtils.parseSpaceSize(rawValue);
      } catch (Exception ex) {
        throw new RuntimeException(ExceptionMessage.KEY_NOT_BYTES.getMessage(key));
      }
    }
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * Gets the value for the given key as a class.
   *
   * @param key the key to get the value for
   * @param <T> the type of the class
   * @return the value for the given key as a class
   */
  public static <T> Class<T> getClass(String key) {
    if (PROPERTIES_MAP.containsKey(key)) {
      String rawValue = PROPERTIES_MAP.get(key);
      try {
        @SuppressWarnings("unchecked")
        Class<T> clazz = (Class<T>) Class.forName(rawValue);
        return clazz;
      } catch (Exception e) {
        LOG.error("requested class could not be loaded: {}", rawValue, e);
        throw Throwables.propagate(e);
      }
    }
    // if key is not found among the default properties
    throw new RuntimeException(ExceptionMessage.INVALID_CONFIGURATION_KEY.getMessage(key));
  }

  /**
   * @return a view of the internal {@link Properties} of as an immutable map
   */
  public static Map<String, String> toMap() {
    return Collections.unmodifiableMap(PROPERTIES_MAP);
  }

  /**
   * Lookup key names to handle ${key} stuff.
   *
   * @param base string to look for
   * @return the key name with the ${key} substituted
   */
  private static String lookup(String base) {
    return lookupRecursively(base, new HashMap<String, String>());
  }

  /**
   * Actual recursive lookup replacement.
   *
   * @param base the String to look for
   * @param found {@link Map} of String that already seen in this path
   * @return resolved String value
   */
  private static String lookupRecursively(final String base, Map<String, String> found) {
    // check argument
    if (base == null) {
      return null;
    }

    String resolved = base;
    // Lets find pattern match to ${key}.
    // TODO(hsaputra): Consider using Apache Commons StrSubstitutor.
    Matcher matcher = CONF_REGEX.matcher(base);
    while (matcher.find()) {
      String match = matcher.group(2).trim();
      String value;
      if (!found.containsKey(match)) {
        value = lookupRecursively(PROPERTIES_MAP.get(match), found);
        found.put(match, value);
      } else {
        value = found.get(match);
      }
      if (value != null) {
        LOG.debug("Replacing {} with {}", matcher.group(1), value);
        resolved = resolved.replaceFirst(REGEX_STRING, Matcher.quoteReplacement(value));
      }
    }
    return resolved;
  }

  /**
   * {@link Constants#USER_FILE_BUFFER_BYTES} should not bigger than {@link Integer#MAX_VALUE}
   * bytes.
   *
   * @throws IllegalArgumentException if USER_FILE_BUFFER_BYTES bigger than Integer.MAX_VALUE
   */
  private static void checkUserFileBufferBytes() {
    if (!containsKey(Constants.USER_FILE_BUFFER_BYTES)) { // load from hadoop conf
      return;
    }
    long usrFileBufferBytes = getBytes(Constants.USER_FILE_BUFFER_BYTES);
    Preconditions.checkArgument((usrFileBufferBytes & Integer.MAX_VALUE) == usrFileBufferBytes,
        "Invalid \"" + Constants.USER_FILE_BUFFER_BYTES + "\": " + usrFileBufferBytes);
  }

  private Configuration() {} // prevent instantiation
}
