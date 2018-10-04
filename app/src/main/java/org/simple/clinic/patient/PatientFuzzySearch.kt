package org.simple.clinic.patient

import android.arch.persistence.db.SimpleSQLiteQuery
import android.arch.persistence.db.SupportSQLiteOpenHelper
import io.reactivex.Single
import io.reactivex.SingleTransformer
import timber.log.Timber
import java.util.UUID

class PatientFuzzySearch {

  data class UuidToScore(val uuid: UUID, val score: Int)

  // TODO: See if this can merged with the PatientSearchDao.
  interface PatientFuzzySearchDao {

    fun searchForPatientsWithNameLike(query: String): Single<List<PatientSearchResult>>

    fun searchForPatientsWithNameLikeAndAgeWithin(query: String, dobUpperBound: String, dobLowerBound: String): Single<List<PatientSearchResult>>
  }

  class PatientFuzzySearchDaoImpl(
      private val sqLiteOpenHelper: SupportSQLiteOpenHelper,
      private val patientSearchDao: PatientSearchResult.RoomDao
  ) : PatientFuzzySearchDao {

    private val cutOffUpperMultiplier = 1.4f
    private val cutOffLowerMultiplier = 0.8f

    override fun searchForPatientsWithNameLike(query: String) =
        patientUuidsMatching(query)
            .flatMap { uuidsSortedByScore ->
              val uuids = uuidsSortedByScore.map { it.uuid }
              patientSearchDao
                  .searchByIds(uuids)
                  .compose(sortPatientSearchResultsByScore(uuidsSortedByScore))
            }!!

    override fun searchForPatientsWithNameLikeAndAgeWithin(query: String, dobUpperBound: String, dobLowerBound: String) =
        patientUuidsMatching(query).flatMap { uuidsSortedByScore ->
          val uuids = uuidsSortedByScore.map { it.uuid }
          patientSearchDao
              .searchByIds(uuids, dobUpperBound, dobLowerBound)
              .compose(sortPatientSearchResultsByScore(uuidsSortedByScore))
        }!!

    private fun patientUuidsMatching(query: String): Single<List<UuidToScore>> {
      Timber.tag("Fuzzy").d("SEARCH: $query")
      return Single.fromCallable {
        val searchQuery = SimpleSQLiteQuery("""
          SELECT "Patient"."uuid", editdist3('$query', "Patient"."searchableName") "score"
          FROM "Patient" WHERE "score" < 750
          ORDER BY "score"
        """.trimIndent())

        val searchQuery2 = SimpleSQLiteQuery("""
          SELECT "Patient"."uuid", "Patient"."searchableName" "name",
          ((abs(length("Patient"."searchableName") - length('$query')) + 1) * $cutOffUpperMultiplier * 100) "cutoffUpper",
          ((abs(length("Patient"."searchableName") - length('$query')) + 1) * $cutOffLowerMultiplier * 100) "cutoffLower",
          editdist3(lower('$query'), lower("Patient"."searchableName")) "score"
          FROM "Patient" WHERE "score" <= 10000
          ORDER BY "score"
        """.trimIndent())

        // FROM "Patient" WHERE "score" <= "cutoffUpper"
        // FROM "Patient" WHERE "score" <= 10000
        // FROM "Patient" WHERE ("score" BETWEEN "cutOffLower" AND "cutoffUpper") OR ("score" == 0)

        sqLiteOpenHelper.readableDatabase.query(searchQuery2)
            .use { cursor ->
              val uuidIndex = cursor.getColumnIndex("uuid")
              val scoreIndex = cursor.getColumnIndex("score")

              val nameIndex = cursor.getColumnIndex("name")
              val cutoffUpperIndex = cursor.getColumnIndex("cutoffUpper")
              val cutoffLowerIndex = cursor.getColumnIndex("cutoffLower")

              generateSequence { cursor.takeIf { it.moveToNext() } }
                  .onEach {
                    val name = cursor.getString(nameIndex)
                    val cutOffUpper = cursor.getFloat(cutoffUpperIndex)
                    val cutOffLower = cursor.getFloat(cutoffLowerIndex)

                    Timber.tag("Fuzzy").d("Cutoff #${cursor.position}: $name -> [$cutOffLower, ${cursor.getInt(scoreIndex)}, $cutOffUpper]")
                  }
                  .map { UuidToScore(UUID.fromString(cursor.getString(uuidIndex)), cursor.getInt(scoreIndex)) }
                  .toList()
            }
      }
    }

    private fun sortPatientSearchResultsByScore(uuidsSortedByScore: List<UuidToScore>) =
        SingleTransformer<List<PatientSearchResult>, List<PatientSearchResult>> { upstream ->
          upstream
              .map { results ->
                val resultsByUuid = results.associateBy { it.uuid }
                uuidsSortedByScore
                    .filter { it.uuid in resultsByUuid }
                    .map { resultsByUuid[it.uuid]!! }
              }
        }
  }
}
