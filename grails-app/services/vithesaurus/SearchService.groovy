package vithesaurus

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import com.vionto.vithesaurus.PartialMatch
import com.vionto.vithesaurus.tools.DbUtils
import org.apache.commons.lang.StringUtils
import com.vionto.vithesaurus.SimilarMatch

class SearchService {

  static transactional = false

  def dataSource

  private static final int MAX_SIMILARITY_DISTANCE = 3

  List searchWikipedia(String query, Connection conn) {
    String sql = """SELECT link, title FROM wikipedia_links, wikipedia_pages
          WHERE wikipedia_pages.title = ? AND wikipedia_pages.page_id = wikipedia_links.page_id"""
    PreparedStatement ps = null
    ResultSet resultSet = null
    List matches = []
    try {
        ps = conn.prepareStatement(sql)
        ps.setString(1, query)
        resultSet = ps.executeQuery()
        int i = 0
        while (resultSet.next()) {
          if (i == 0) {
            matches.add(resultSet.getString("title"))
          }
          matches.add(resultSet.getString("link"))
          i++
        }
    } finally {
      DbUtils.closeQuietly(resultSet)
      DbUtils.closeQuietly(ps)
    }
    return matches
  }

  List searchWiktionary(String query, Connection conn) {
    String sql = "SELECT headword, meanings, synonyms FROM wiktionary WHERE headword = ?"
    PreparedStatement ps = null
    ResultSet resultSet = null
    def matches = []
    try {
      ps = conn.prepareStatement(sql)
      ps.setString(1, query)
      resultSet = ps.executeQuery()
      if (resultSet.next()) {
        matches.add(resultSet.getString("headword"))
        matches.add(resultSet.getString("meanings"))
        matches.add(resultSet.getString("synonyms"))
      }
    } finally {
      DbUtils.closeQuietly(resultSet)
      DbUtils.closeQuietly(ps)
    }
    return matches
  }

  def searchSimilarTerms(String query, Connection conn) {
    String sql = """SELECT word, lookup FROM memwords WHERE (
              (CHAR_LENGTH(word) >= ? AND CHAR_LENGTH(word) <= ?)
              OR
              (CHAR_LENGTH(lookup) >= ? AND CHAR_LENGTH(lookup) <= ?))
              ORDER BY word"""
    PreparedStatement ps = null
    ResultSet resultSet = null
    def matches = []
    try {
        ps = conn.prepareStatement(sql)
        int wordLength = query.length()
        ps.setInt(1, wordLength-1)
        ps.setInt(2, wordLength+1)
        ps.setInt(3, wordLength-1)
        ps.setInt(4, wordLength+1)
        resultSet = ps.executeQuery()
        // TODO: add some typical cases to be found without levenshtein (s <-> ß, ...)
        String lowerTerm = query.toLowerCase()
        while (resultSet.next()) {
          String dbTerm = resultSet.getString("word").toLowerCase()
          if (dbTerm.equals(lowerTerm)) {
            continue
          }
          //TODO: use a fail-fast algorithm here (see Lucene's FuzzyTermQuery):
          int dist = StringUtils.getLevenshteinDistance(dbTerm, lowerTerm)
          if (dist <= MAX_SIMILARITY_DISTANCE) {
            matches.add(new SimilarMatch(term:resultSet.getString("word"), dist:dist))
          } else {
            dbTerm = resultSet.getString("lookup")
            if (dbTerm) {
              dbTerm = dbTerm.toLowerCase()
              dist = StringUtils.getLevenshteinDistance(dbTerm, lowerTerm)
              if (dist <= MAX_SIMILARITY_DISTANCE) {
                matches.add(new SimilarMatch(term:resultSet.getString("word"), dist:dist))
              }
            }
          }
        }
        Collections.sort(matches)		// makes sure lowest distances come first
    } finally {
        DbUtils.closeQuietly(resultSet)
        DbUtils.closeQuietly(ps)
    }
    return matches
  }

  /** Substring matches */
  List searchPartialResult(String term, int fromPos, int maxNum) {
    return searchPartialResultInternal(term, "%" + term + "%", true, fromPos, maxNum)
  }

  /** Words that start with a given term */
  List searchStartsWithResult(String term, int fromPos, int maxNum) {
    return searchPartialResultInternal(term, term + "%", false, fromPos, maxNum)
  }

  int getPartialResultTotalMatches(String query) {
    Connection conn = null
    PreparedStatement ps = null
    ResultSet resultSet = null
    try {
      conn = dataSource.getConnection()
      String sql = "SELECT count(*) AS totalMatches FROM memwords WHERE word LIKE ?"
      ps = conn.prepareStatement(sql)
      ps.setString(1, "%" + query + "%")
      resultSet = ps.executeQuery()
      resultSet.next()
      return resultSet.getInt("totalMatches")
    } finally {
      DbUtils.closeQuietly(resultSet)
      DbUtils.closeQuietly(ps)
      DbUtils.closeQuietly(conn)
    }
  }

  /** Substring matches */
  private List searchPartialResultInternal(String term, String sqlTerm, boolean filterExactMatch, int fromPos, int maxNum) {
    Connection conn = null
    PreparedStatement ps = null
    ResultSet resultSet = null
    List matches = []
    try {
      conn = dataSource.getConnection()
      String sql = "SELECT word FROM memwords WHERE word LIKE ? ORDER BY word ASC LIMIT ${fromPos}, ${maxNum}"
      ps = conn.prepareStatement(sql)
      ps.setString(1, sqlTerm)
      resultSet = ps.executeQuery()
      while (resultSet.next()) {
        String matchedTerm = resultSet.getString("word")
        if (filterExactMatch && matchedTerm.toLowerCase() == term.toLowerCase()) {
          continue
        }
        String result = matchedTerm.encodeAsHTML()
        matches.add(new PartialMatch(term:matchedTerm, highlightTerm:result))
      }
    } finally {
      DbUtils.closeQuietly(resultSet)
      DbUtils.closeQuietly(ps)
      DbUtils.closeQuietly(conn)
    }
    return matches
  }

}
