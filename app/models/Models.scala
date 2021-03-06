package models

import utils.Crypto
import utils.JsonFormatters._
import utils.Validator._
import utils.Validator
import utils.ValidationException

import play.api.Play.current
import play.api.db.slick.Config.driver.simple._
import scala.slick.lifted.Tag
import play.api.libs.json._

import java.sql.Timestamp
import java.util.Date

/** vote object */
case class Vote(id: Option[Long], election_id: Long, voter_id: String, vote: String, hash: String, created: Timestamp)

/** relational representation of votes */
class Votes(tag: Tag) extends Table[Vote](tag, "vote") {
  def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
  def electionId = column[Long]("election_id", O.NotNull)
  def voterId = column[String]("voter_id", O.NotNull, O.DBType("text"))
  def vote = column[String]("vote", O.NotNull, O.DBType("text"))
  def hash = column[String]("hash",  O.NotNull, O.DBType("text"))
  def created = column[Timestamp]("created", O.NotNull)
  def * = (id.?, electionId, voterId, vote, hash, created) <> (Vote.tupled, Vote.unapply _)
}

/** data access object for votes */
object Votes {

  val votes = TableQuery[Votes]

  def insert(vote: Vote)(implicit s: Session) = {
    (votes returning votes.map(_.id)) += vote
  }

  def findByVoterId(voterId: String)(implicit s: Session): List[Vote] = votes.filter(_.voterId === voterId).list

  def findByElectionId(electionId: Long)(implicit s: Session): List[Vote] = votes.filter(_.electionId === electionId).list

  def findByElectionIdRange(electionId: Long, drop: Long, take: Long)(implicit s: Session): List[Vote] = {
    votes.filter(_.electionId === electionId).sortBy(_.created.desc).drop(drop).take(take).list
  }

  def checkHash(electionId: Long, hash: String)(implicit s: Session): Option[Vote] = {
    val vote = votes.filter(_.electionId === electionId).filter(_.hash === hash).firstOption

    // we make sure the hash corresponds to the last vote, otherwise return None
    vote.flatMap { v =>
      val latest = votes.filter(_.electionId === electionId).filter(_.voterId === v.voter_id).sortBy(_.created.desc).firstOption
      latest.filter(_.hash == hash)
    }
  }

  // def count(implicit s: Session): Int = Query(votes.length).first
  def count(implicit s: Session): Int = votes.length.run

  def countForElection(electionId: Long)(implicit s: Session): Int = votes.filter(_.electionId === electionId).length.run
  def countUniqueForElection(electionId: Long)(implicit s: Session): Int = votes.filter(_.electionId === electionId).groupBy(v=>v.voterId).map(_._1).length.run

  def countForElectionAndVoter(electionId: Long, voterId: String)(implicit s: Session): Int = {
    votes.filter(_.electionId === electionId).filter(_.voterId === voterId).length.run
  }
}

/** election object */
case class Election(id: Long, configuration: String, state: String, startDate: Timestamp, endDate: Timestamp,
  pks: Option[String], results: Option[String], resultsUpdated: Option[Timestamp]) {

  def getDTO = {
    var configJson = Json.parse(configuration)
    if (!configJson.as[JsObject].keys.contains("layout")) {
        configJson = configJson.as[JsObject] + ("layout" -> Json.toJson("simple"))
    }
    var config = configJson.validate[ElectionConfig].get
    ElectionDTO(id, config, state, startDate, endDate, pks, results, resultsUpdated)
  }
}

/** relational representation of elections */
class Elections(tag: Tag) extends Table[Election](tag, "election") {
  def id = column[Long]("id", O.PrimaryKey)
  def configuration = column[String]("configuration", O.NotNull, O.DBType("text"))
  def state = column[String]("state", O.NotNull)
  def startDate = column[Timestamp]("start_date", O.NotNull)
  def endDate = column[Timestamp]("end_date", O.NotNull)
  def pks = column[String]("pks", O.Nullable, O.DBType("text"))
  def results = column[String]("results", O.Nullable, O.DBType("text"))
  def resultsUpdated = column[Timestamp]("results_updated", O.Nullable)
  def * = (id, configuration, state, startDate, endDate, pks.?, results.?, resultsUpdated.?) <> (Election.tupled, Election.unapply _)
}

/** data access object for elections */
object Elections {
  val REGISTERED = "registered"
  val CREATED = "created"
  val CREATE_ERROR = "create_error"
  val STARTED = "started"
  val STOPPED = "stopped"
  val TALLY_OK = "tally_ok"
  val TALLY_ERROR = "tally_error"
  val RESULTS_OK = "results_ok"
  val DOING_TALLY = "doing_tally"

  val elections = TableQuery[Elections]

  def findById(id: Long)(implicit s: Session): Option[Election] = elections.filter(_.id === id).firstOption

  def count(implicit s: Session): Int = elections.length.run

  def insert(election: Election)(implicit s: Session) = {
    (elections returning elections.map(_.id)) += election
  }

  def update(theId: Long, election: Election)(implicit s: Session) = {
    val electionToWrite = election.copy(id = theId)
    elections.filter(_.id === theId).update(electionToWrite)
  }

  def updateState(id: Long, state: String)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => e.state).update(state)
  }

  def updateResults(id: Long, results: String)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => (e.state, e.results, e.resultsUpdated))
    .update(Elections.RESULTS_OK, results, new Timestamp(new Date().getTime))
  }

  def updateConfig(id: Long, config: String, start: Timestamp, end: Timestamp)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => (e.configuration, e.startDate, e.endDate)).update(config, start, end)
  }

  def setPublicKeys(id: Long, pks: String)(implicit s: Session) = {
    elections.filter(_.id === id).map(e => (e.state, e.pks)).update(CREATED, pks)
  }

  def delete(id: Long)(implicit s: Session) = {
    elections.filter(_.id === id).delete
  }
}

/*-------------------------------- transient models  --------------------------------*/

case class Stats(totalVotes: Long, votes: Long)

/** used to return an election with config in structured form */
case class ElectionDTO(id: Long, configuration: ElectionConfig, state: String, startDate: Timestamp,
  endDate: Timestamp, pks: Option[String], results: Option[String], resultsUpdated: Option[Timestamp])

/** an election configuration defines an election */
case class ElectionConfig(id: Long, layout: String, director: String, authorities: Array[String], title: String, description: String,
  questions: Array[Question], start_date: Timestamp, end_date: Timestamp, presentation: ElectionPresentation) {

  /**
    * validates an election config, this does two things:
    *
    * 1) validation: throws ValidationException if the content cannot be made valid
    *
    * 2) sanitation: transforms the content that can be made valid
    *
    * returns a valid ElectionConfig
    *
    */
  def validate(peers: Map[String, JsObject], id2: Long) = {

    assert(id >= 0, s"Invalid id $id")
    validateIdentifier(layout, "invalid layout")
    assert(id == id2, s"Invalid id $id")
    // validate authorities
    val auths = (director +: authorities).toSet

    assert(auths.size >= 2, s"Need at least two authorities (${auths.size})")

    // make sure that all requested authorities are available as peers
    auths.foreach { auth =>
      assert(peers.contains(auth), "One or more authorities were not found")
    }

    validateStringLength(title, LONG_STRING, s"title too large: $title")
    assert(description.length <= LONG_STRING, "description too long")
    val descriptionOk = sanitizeHtml(description)

    assert(questions.size >= 1, "need at least one queston")
    val questionsOk = questions.map(_.validate())

    // TODO
    // start_date
    // end_date

    val presentationOk = presentation.validate()

    this.copy(description = descriptionOk, questions = questionsOk, presentation = presentationOk)
  }

  /** returns a json string representation */
  def asString() = {
    Json.stringify(Json.toJson(this))
  }
}

/** defines a question asked in an election */
case class Question(description: String, layout: String, max: Int, min: Int, num_winners: Int, title: String,
  randomize_answer_order: Boolean, tally_type: String, answer_total_votes_percentage: String, answers: Array[Answer]) {

  def validate() = {

    assert(description.length <= LONG_STRING, "description too long")
    val descriptionOk = sanitizeHtml(description)

    validateIdentifier(layout, "invalid layout")
    assert(max >= 1, "invalid max")
    assert(max <= answers.size, "max greater than answers")
    assert(min >= 0, "invalid min")
    assert(min <= answers.size, "min greater than answers")
    assert(num_winners >= 1, "invalid num_winners")
    assert(num_winners <= answers.size, "num_winners greater than answers")
    validateStringLength(title, LONG_STRING, s"title too large: $title")
    // TODO not looking inside the value
    validateIdentifier(tally_type, "invalid tally_type")
    // TODO not looking inside the value
    validateIdentifier(answer_total_votes_percentage, "invalid answer_total_votes_percentage")
    val answersOk = answers.map(_.validate())

    this.copy(description = descriptionOk, answers = answersOk)
  }
}

/** defines a possible answer for a question asked in an election */
case class Answer(id: Int, category: String, details: String, sort_order: Int, urls: Array[Url], text: String) {

  def validate() = {
    assert(id >= 0, "invalid id")
    validateStringLength(category, SHORT_STRING, s"category too large $category")

    assert(details.length <= LONG_STRING, "details too long")
    val detailsOk = sanitizeHtml(details)
    // TODO not looking inside the value
    assert(sort_order >= 0, "invalid sort_order")
    assert(text.length <= LONG_STRING, "text too long")
    val textOk = sanitizeHtml(text)
    val urlsOk = urls.map(_.validate())

    this.copy(details = detailsOk, urls = urlsOk, text = textOk)
  }
}

/** defines presentation options for an election */
case class ElectionPresentation(share_text: String, theme: String, urls: Array[Url], theme_css: String) {

  def validate() = {

    validateStringLength(share_text, LONG_STRING, s"share_text too large $share_text")
    validateIdentifier(theme, "invalid theme")
    val urlsOk = urls.map(_.validate())
    validateIdentifier(theme_css, "invalid theme_css")

    this.copy(urls = urlsOk)
  }
}

/** an url to be shown when presenting election data */
case class Url(title: String, url: String) {

  def validate() = {
    validateStringLength(title, SHORT_STRING, s"invalid url title $title")
    validateUrl(url, s"invalid url $url")

    this
  }
}


/** eo create election response message */
case class CreateResponse(status: String, session_data: Array[PublicKeySession])

/** eo public key message component */
case class PublicKeySession(pubkey: PublicKey, session_id: String)

/** el gamal public key */
case class PublicKey(q: BigInt, p: BigInt, y:BigInt, g: BigInt)

/** eo tally election response message */
case class TallyResponse(status: String, data: TallyData)

/** eo tally data message component */
case class TallyData(tally_url: String, tally_hash: String)


/** json vote submitted to the ballot box, when validated becomes a Vote */
case class VoteDTO(vote: String, vote_hash: String) {
  def validate(pks: Array[PublicKey], checkResidues: Boolean, electionId: Long, voterId: String) = {
    val json = Json.parse(vote)
    val encryptedValue = json.validate[EncryptedVote]

    encryptedValue.fold (
      errors => throw new ValidationException(s"Error parsing vote json: $errors"),
      encrypted => {

        encrypted.validate(pks, checkResidues)

        val hashed = Crypto.sha256(vote)

        if(hashed != vote_hash) throw new ValidationException("Hash mismatch")

        Vote(None, electionId, voterId, vote, vote_hash, new Timestamp(new Date().getTime))
      }
    )
  }
}

/** the ciphertext present in a json vote (VoteDTO), including proofs of plaintext knowledge */
case class EncryptedVote(choices: Array[Choice], issue_date: String, proofs: Array[Popk]) {

  /** ciphertext validation: choice is quadratic residue and validation of proof of plaintext knowledge */
  def validate(pks: Array[PublicKey], checkResidues: Boolean) = {

    if(checkResidues) {
      choices.zipWithIndex.foreach { case (choice, index) =>
        choice.validate(pks(index))
      }
    }

    checkPopk(pks)
  }

  /** validates proof of plaintext knowledge, schnorr protocol */
  def checkPopk(pks: Array[PublicKey]) = {

    proofs.zipWithIndex.foreach { case (proof, index) =>
      val choice = choices(index)

      val toHash = s"${choice.alpha.toString}/${proof.commitment.toString}"
      val hashed = Crypto.sha256(toHash)
      val expected = BigInt(hashed, 16)

      if (!proof.challenge.equals(expected)) {
        throw new ValidationException("Popk hash mismatch")
      }

      val pk = pks(index)

      val first = pk.g.modPow(proof.response, pk.p)
      val second = (choice.alpha.modPow(proof.challenge, pk.p) * proof.commitment).mod(pk.p)

      if(!first.equals(second)) {
        throw new ValidationException("Failed verifying popk")
      }
    }
  }
}

/** the el-gamal ciphertext itself */
case class Choice(alpha: BigInt, beta: BigInt) {

  /** checks that both alpha and beta are quadratic residues in p */
  def validate(pk: PublicKey) = {

    if(!Crypto.quadraticResidue(alpha, pk.p)) throw new ValidationException("Alpha quadratic non-residue")
    if(!Crypto.quadraticResidue(beta, pk.p)) throw new ValidationException("Beta quadratic non-residue")
  }
}

/** proof of plaintext knowledge, according to schnorr protocol*/
case class Popk(challenge: BigInt, commitment: BigInt, response: BigInt)

/** data describing an authority, used in admin interface */
case class AuthData(name: Option[String], description: Option[String], url: Option[String], image: Option[String])
