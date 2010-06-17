package de.fuberlin.wiwiss.silk.datasource

import de.fuberlin.wiwiss.silk.Instance

trait InstanceCache
{
    /**
     * Writes to this cache.
     * All previous partitions will be deleted.
     */
    def write(instances : Traversable[Instance], blockingFunction : Instance => Option[Int] = _ => Some(0)) : Unit

    /**
     * Reads a partition of a block.
     */
    def read(block : Int, partition : Int) : Array[Instance]

    /**
     * The number of blocks in this cache.
     */
    val blockCount : Int

    /**
     * The number of partitions in a specific block.
     */
    def partitionCount(block : Int) : Int
}
