package org.silkframework.dataset

import org.silkframework.runtime.plugin.{PluginFactory, AnyPlugin}

/**
 * Manages the access to a specific dataset.
 */
trait DatasetPlugin extends AnyPlugin {

  /**
   * Returns a data source for reading entities from the data set.
   */
  def source: DataSource

  /**
   * Returns a link sink for writing entity links to the data set.
   */
  def linkSink: LinkSink

  /**
   * Returns a entity sink for writing entities to the data set.
   */
  def entitySink: EntitySink

  /**
    * Makes sure that the next write will start from an empty dataset.
    */
  def clear(): Unit

}


/**
 * Creates new dataset olugin instances.
 */
object DatasetPlugin extends PluginFactory[DatasetPlugin]