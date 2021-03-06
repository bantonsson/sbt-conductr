/*
 * Copyright © 2014 Typesafe, Inc. All rights reserved.
 */

package com.typesafe.conductr.sbt

import sbt._
import sbt.complete.DefaultParsers._
import sbt.complete.Parser

import com.typesafe.sbt.packager.Keys._
import scala.concurrent.duration.DurationInt
import language.postfixOps
import ConductR._

/**
 * An sbt plugin that interact's with Typesafe ConductR's controller and potentially other components.
 */
object ConductRPlugin extends AutoPlugin {
  import com.typesafe.sbt.bundle.SbtBundle.autoImport._
  import Import._
  import sbinary.DefaultProtocol.FileFormat

  val autoImport = Import

  override def trigger = allRequirements

  override def globalSettings: Seq[Setting[_]] =
    super.globalSettings ++ List(
      Keys.onLoad := Keys.onLoad.value.andThen(loadActorSystem).andThen(loadConductRController),
      Keys.onUnload := (unloadConductRController _).andThen(unloadActorSystem).andThen(Keys.onUnload.value),

      Keys.aggregate in ConductRKeys.conduct := false,

      dist in Bundle := file(""),
      dist in BundleConfiguration := file(""),

      ConductRKeys.conductrConnectTimeout := 30.seconds,
      ConductRKeys.conductrLoadTimeout := 10.minutes,
      ConductRKeys.conductrRequestTimeout := 30.seconds,

      ConductRKeys.conductrControlServerUrl := envUrl("CONDUCTR_IP", DefaultConductrHost, "CONDUCTR_PORT", DefaultConductrPort, DefaultConductrProtocol),
      ConductRKeys.conductrLoggingQueryUrl := envUrl("LOGGING_QUERY_IP", DefaultConductrHost, "LOGGING_QUERY_PORT", DefaultConductrPort, DefaultConductrProtocol),

      ConductRKeys.conductrApiVersion := "1.0"
    )

  override def projectSettings: Seq[Setting[_]] =
    super.projectSettings ++ List(
      Keys.commands ++= Seq(controlServer),
      ConductRKeys.conduct := conductTask.value.evaluated,

      ConductRKeys.conductrDiscoveredDist <<=
        (dist in Bundle).storeAs(ConductRKeys.conductrDiscoveredDist)
        .triggeredBy(dist in Bundle),
      ConductRKeys.conductrDiscoveredConfigDist <<=
        (dist in BundleConfiguration).storeAs(ConductRKeys.conductrDiscoveredConfigDist)
        .triggeredBy(dist in BundleConfiguration)
    )

  // Input parsing and action

  private def controlServer: Command = Command.single("controlServer") { (prevState, url) =>
    val extracted = Project.extract(prevState)
    extracted.append(Seq(ConductRKeys.conductrControlServerUrl in Global := prepareConductrUrl(url)), prevState)
  }

  private object Parsers {
    lazy val subtask: Def.Initialize[State => Parser[Option[ConductSubtask]]] = {
      val init = Def.value { (bundle: Option[File], bundleConfig: Option[File]) =>
        (Space ~> (
          loadSubtask(bundle, bundleConfig) |
          runSubtask |
          stopSubtask |
          unloadSubtask |
          infoSubtask |
          eventsSubtask |
          logsSubtask)) ?
      }
      (Keys.resolvedScoped, init) { (ctx, parser) =>
        s: State =>
          val bundle = loadFromContext(ConductRKeys.conductrDiscoveredDist, ctx, s)
          val bundleConfig = loadFromContext(ConductRKeys.conductrDiscoveredConfigDist, ctx, s)
          parser(bundle, bundleConfig)
      }
    }
    def loadSubtask(availableBundle: Option[File], availableBundleConfiguration: Option[File]): Parser[LoadSubtask] =
      (token("load") ~> Space ~> bundle(availableBundle) ~
        bundleConfiguration(availableBundleConfiguration).?) map { case (b, config) => LoadSubtask(b, config) }
    def runSubtask: Parser[RunSubtask] =
      // FIXME: Should default to last loadBundle result
      (token("run") ~> Space ~> bundleId(List("fixme")) ~ scale.?) map { case (b, scale) => RunSubtask(b, scale) }
    def stopSubtask: Parser[StopSubtask] =
      // FIXME: Should default to last bundle started
      (token("stop") ~> Space ~> bundleId(List("fixme"))) map { case b => StopSubtask(b) }
    def unloadSubtask: Parser[UnloadSubtask] =
      // FIXME: Should default to last bundle loaded
      (token("unload") ~> Space ~> bundleId(List("fixme"))) map { case b => UnloadSubtask(b) }
    def infoSubtask: Parser[InfoSubtask.type] =
      token("info") map { case _ => InfoSubtask }
    def eventsSubtask: Parser[EventsSubtask] =
      (token("events") ~> Space ~> bundleId(List("fixme")) ~ lines.?) map { case (b, lines) => EventsSubtask(b, lines) }
    def logsSubtask: Parser[LogsSubtask] =
      (token("logs") ~> Space ~> bundleId(List("fixme")) ~ lines.?) map { case (b, lines) => LogsSubtask(b, lines) }

    def bundle(bundle: Option[File]): Parser[URI] =
      token(Uri(bundle.fold[Set[URI]](Set.empty)(f => Set(f.toURI))))

    def bundleConfiguration(bundleConf: Option[File]): Parser[URI] = Space ~> bundle(bundleConf)

    def bundleId(x: Seq[String]): Parser[String] = StringBasic examples (x: _*)

    def positiveNumber: Parser[Int] = Space ~> NatBasic

    def scale: Parser[Int] = Space ~> "--scale" ~> positiveNumber

    def lines: Parser[Int] = Space ~> "--lines" ~> positiveNumber
  }

  private sealed trait ConductSubtask
  private case class LoadSubtask(bundle: URI, config: Option[URI]) extends ConductSubtask
  private case class RunSubtask(bundleId: String, scale: Option[Int]) extends ConductSubtask
  private case class StopSubtask(bundleId: String) extends ConductSubtask
  private case class UnloadSubtask(bundleId: String) extends ConductSubtask
  private case object InfoSubtask extends ConductSubtask
  private case class EventsSubtask(bundleId: String, lines: Option[Int]) extends ConductSubtask
  private case class LogsSubtask(bundleId: String, lines: Option[Int]) extends ConductSubtask

  private def conductTask: Def.Initialize[InputTask[Unit]] =
    Def.inputTask {
      val apiVersion = ConductRKeys.conductrApiVersion.value
      val state = Keys.state.value
      val loadTimeout = ConductRKeys.conductrLoadTimeout.value
      val requestTimeout = ConductRKeys.conductrRequestTimeout.value
      val subtaskOpt: Option[ConductSubtask] = Parsers.subtask.parsed
      subtaskOpt match {
        case Some(LoadSubtask(bundle, config))    => ConductR.loadBundle(apiVersion, bundle, config, loadTimeout, state)
        case Some(RunSubtask(bundleId, scale))    => ConductR.runBundle(apiVersion, bundleId, scale, requestTimeout, state)
        case Some(StopSubtask(bundleId))          => ConductR.stopBundle(apiVersion, bundleId, requestTimeout, state)
        case Some(UnloadSubtask(bundleId))        => ConductR.unloadBundleTask(apiVersion, bundleId, requestTimeout, state)
        case Some(InfoSubtask)                    => ConductR.info(apiVersion, state)
        case Some(EventsSubtask(bundleId, lines)) => ConductR.events(apiVersion, bundleId, lines, state)
        case Some(LogsSubtask(bundleId, lines))   => ConductR.logs(apiVersion, bundleId, lines, state)
        case None                                 => println("Usage: conduct <subtask>")
      }
    }
}
