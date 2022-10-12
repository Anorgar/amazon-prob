package fr.amazon.prob;


import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Application {

  private static final Logger log = LoggerFactory.getLogger(Application.class);

  public static final String PRODUCT_PAGE = "your product url";
  public static final String FROM_EMAIL = "your gmail";
  public static final String TO_EMAILS = "destination mail";
  public static final String APP_PASSWORD = "your gmail app password";

  private static final String TOP_REGEX = "(\\d+) en.*";
  private static final Pattern TOP_PATTERN = Pattern.compile(TOP_REGEX);
  private static final String TOTAL_REGEX = ".* ((\\d+,)?\\d+) en Livres.*";
  private static final Pattern TOTAL_PATTERN = Pattern.compile(TOTAL_REGEX);
  public static final String PERSISTED_RANK_FILE = "/home/ubuntu/code/amazon-prob/persisted_rank.txt";
  public static final String STAT_FILE = "/home/ubuntu/code/amazon-prob/stats.csv";
  public static final int AMAZON_TOP_RANK = 100;

  public static void main(String[] args) {
    try {
      Document document = callProductPage();
      int rank = extractBestRankFromProductPage(document);
      int previousRank = getPersistedRank();
      int totalRank = extractTotalRankFromProductPage(document);
      persistCurrentRank(rank);

      try (FileWriter statFile = new FileWriter(STAT_FILE, true)) {
        String stat = LocalDateTime.now()
            + ";"
            + totalRank
            + ";"
            + rank
            + "\n";
        statFile.write(stat);
      } catch (IOException ioe) {
        log.error("Unable to persist stats", ioe);
      }

      if (rank <= AMAZON_TOP_RANK && rank < previousRank) {
        log.info("Current rank {} is good so an email will be sent", rank);
        sendMail(rank);
      } else {
        log.info("Rank {} is too low for an email to be sent", rank);
      }
      log.info("Job finished with success!");
    } catch (RuntimeException e) {
      log.error("Unable to execute bach properly", e);
    }
  }

  private static void persistCurrentRank(int rank) {
    log.info("Start persisting current rank");
    try (PrintWriter printWriter = new PrintWriter(new FileWriter(PERSISTED_RANK_FILE))) {
      printWriter.print(rank);
    } catch (IOException e) {
      log.error("Unable to persist rank", e);
      throw new RuntimeException(e);
    }
    log.info("Current rank is persisted with success");
  }

  private static int getPersistedRank() {
    log.info("Retrieve previously persisted rank");
    try (FileInputStream fis = new FileInputStream(PERSISTED_RANK_FILE)) {
      String data = IOUtils.toString(fis, StandardCharsets.UTF_8);
      if (StringUtils.isNoneBlank(data)) {
        log.info("Previous rank is {}", data);
        return Integer.parseInt(data.replace("\n", "").trim());
      }
      throw new RuntimeException("No persisted Rank found");
    } catch (IOException e) {
      log.error("Unable ro read persisted previous rank", e);
      throw new RuntimeException(e);
    }
  }

  public static Document callProductPage() {
    log.info("Start calling amazon");
    try {
      Document document = Jsoup.connect(PRODUCT_PAGE)
          .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:105.0) Gecko/20100101 Firefox/105.0")
          .header("Accept-Language","fr,fr-FR;q=0.8,en-US;q=0.5,en;q=0.3")
          .cookies(Map.of("i18n-prefs", "EUR; Domain=.amazon.fr; Expires=Thu, 12-Oct-2023 13:39:04 GMT; Path=/"))
          .get();
      log.info("Amazon page successfully fetched");

      return document;
    } catch (IOException e) {
      log.error("Unable to retrieve product page", e);
      throw new RuntimeException("Unable to retrieve product page", e);
    }
  }

  public static int extractBestRankFromProductPage(Document document) {
    log.info("Start extracting best rank");
    Element element = document.getElementById("detailBulletsWrapper_feature_div");
    Elements elementsByClass = element.getElementsByClass("a-list-item");
    int rank = elementsByClass.stream()
        .filter(elem -> elem.text().matches(TOP_REGEX))
        .map(elem -> extractRanking(elem, TOP_PATTERN))
        .filter(Objects::nonNull)
        .sorted()
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Best rank not found in product page"));

    log.info("Best rank for the product is {}", rank);
    return rank;
  }

  public static int extractTotalRankFromProductPage(Document document) {
    log.info("Start extracting total rank");
    Element element = document.getElementById("detailBulletsWrapper_feature_div");
    Elements elementsByClass = element.getElementsByClass("a-list-item");
    int rank = elementsByClass.stream()
        .filter(elem -> elem.text().matches(TOTAL_REGEX))
        .map(elem -> extractRanking(elem, TOTAL_PATTERN))
        .filter(Objects::nonNull)
        .sorted()
        .findFirst()
        .orElseThrow(() -> new RuntimeException("Rank not found in product page"));

    log.info("Total rank for the product is {}", rank);
    return rank;
  }

  public static void sendMail(int rank) {
    log.info("Start sending email");
    Properties prop = new Properties();
    prop.put("mail.smtp.host", "smtp.gmail.com");
    prop.put("mail.smtp.port", "465");
    prop.put("mail.smtp.auth", "true");
    prop.put("mail.smtp.socketFactory.port", "465");
    prop.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");

    Session session = Session.getInstance(prop,
        new Authenticator() {
          protected PasswordAuthentication getPasswordAuthentication() {
            return new PasswordAuthentication(FROM_EMAIL, APP_PASSWORD);
          }
        });

    try {
      Message message = new MimeMessage(session);
      message.setFrom(new InternetAddress(FROM_EMAIL));
      message.setRecipients(
          Message.RecipientType.TO,
          InternetAddress.parse(TO_EMAILS)
      );
      message.setSubject(rank + " rank amazon");
      message.setText("Congratulation !"
          + "\n\n Your actual best amazon rank is " + rank);

      Transport.send(message);

      log.info("Email have been send with success");
    } catch (MessagingException e) {
      log.error("Unable to send mail", e);
      throw new RuntimeException(e);
    }
  }

  public static Integer extractRanking(Element elem, Pattern pattern) {
    Matcher matcher = pattern.matcher(elem.text());
    if (matcher.matches()) {
      return Integer.parseInt(matcher.group(1).replace(",", ""));
    }
    return null;
  }
}
