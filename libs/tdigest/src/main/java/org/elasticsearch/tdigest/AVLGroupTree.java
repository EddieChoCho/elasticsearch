/*
 * Licensed to Elasticsearch B.V. under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch B.V. licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *
 * This project is based on a modification of https://github.com/tdunning/t-digest which is licensed under the Apache 2.0 License.
 */

package org.elasticsearch.tdigest;

import org.elasticsearch.core.Releasable;
import org.elasticsearch.core.Releasables;
import org.elasticsearch.tdigest.arrays.TDigestArrays;
import org.elasticsearch.tdigest.arrays.TDigestDoubleArray;
import org.elasticsearch.tdigest.arrays.TDigestLongArray;

import java.util.AbstractCollection;
import java.util.Iterator;

/**
 * A tree of t-digest centroids.
 */
final class AVLGroupTree extends AbstractCollection<Centroid> implements Releasable {
    /* For insertions into the tree */
    private double centroid;
    private long count;
    private final TDigestDoubleArray centroids;
    private final TDigestLongArray counts;
    private final TDigestLongArray aggregatedCounts;
    private final IntAVLTree tree;

    AVLGroupTree(TDigestArrays arrays) {
        tree = new IntAVLTree(arrays) {

            @Override
            protected void resize(int newCapacity) {
                super.resize(newCapacity);
                centroids.resize(newCapacity);
                counts.resize(newCapacity);
                aggregatedCounts.resize(newCapacity);
            }

            @Override
            protected void merge(int node) {
                // two nodes are never considered equal
                throw new UnsupportedOperationException();
            }

            @Override
            protected void copy(int node) {
                centroids.set(node, centroid);
                counts.set(node, count);
            }

            @Override
            protected int compare(int node) {
                if (centroid < centroids.get(node)) {
                    return -1;
                } else {
                    // upon equality, the newly added node is considered greater
                    return 1;
                }
            }

            @Override
            protected void fixAggregates(int node) {
                super.fixAggregates(node);
                aggregatedCounts.set(node, counts.get(node) + aggregatedCounts.get(left(node)) + aggregatedCounts.get(right(node)));
            }

        };
        centroids = arrays.newDoubleArray(tree.capacity());
        counts = arrays.newLongArray(tree.capacity());
        aggregatedCounts = arrays.newLongArray(tree.capacity());
    }

    /**
     * Return the number of centroids in the tree.
     */
    public int size() {
        return tree.size();
    }

    /**
     * Return the previous node.
     */
    public int prev(int node) {
        return tree.prev(node);
    }

    /**
     * Return the next node.
     */
    public int next(int node) {
        return tree.next(node);
    }

    /**
     * Return the mean for the provided node.
     */
    public double mean(int node) {
        return centroids.get(node);
    }

    /**
     * Return the count for the provided node.
     */
    public long count(int node) {
        return counts.get(node);
    }

    /**
     * Add the provided centroid to the tree.
     */
    public void add(double centroid, long count) {
        this.centroid = centroid;
        this.count = count;
        tree.add();
    }

    @Override
    public boolean add(Centroid centroid) {
        add(centroid.mean(), centroid.count());
        return true;
    }

    /**
     * Update values associated with a node, readjusting the tree if necessary.
     */
    public void update(int node, double centroid, long count) {
        // have to do full scale update
        this.centroid = centroid;
        this.count = count;
        tree.update(node);
    }

    /**
     * Return the last node whose centroid is less than <code>centroid</code>.
     */
    public int floor(double centroid) {
        int floor = IntAVLTree.NIL;
        for (int node = tree.root(); node != IntAVLTree.NIL;) {
            final int cmp = Double.compare(centroid, mean(node));
            if (cmp <= 0) {
                node = tree.left(node);
            } else {
                floor = node;
                node = tree.right(node);
            }
        }
        return floor;
    }

    /**
     * Return the last node so that the sum of counts of nodes that are before
     * it is less than or equal to <code>sum</code>.
     */
    public int floorSum(long sum) {
        int floor = IntAVLTree.NIL;
        for (int node = tree.root(); node != IntAVLTree.NIL;) {
            final int left = tree.left(node);
            final long leftCount = aggregatedCounts.get(left);
            if (leftCount <= sum) {
                floor = node;
                sum -= leftCount + count(node);
                node = tree.right(node);
            } else {
                node = tree.left(node);
            }
        }
        return floor;
    }

    /**
     * Return the least node in the tree.
     */
    public int first() {
        return tree.first(tree.root());
    }

    /**
     * Return the least node in the tree.
     */
    public int last() {
        return tree.last(tree.root());
    }

    /**
     * Compute the number of elements and sum of counts for every entry that
     * is strictly before <code>node</code>.
     */
    public long headSum(int node) {
        final int left = tree.left(node);
        long sum = aggregatedCounts.get(left);
        for (int n = node, p = tree.parent(node); p != IntAVLTree.NIL; n = p, p = tree.parent(n)) {
            if (n == tree.right(p)) {
                final int leftP = tree.left(p);
                sum += counts.get(p) + aggregatedCounts.get(leftP);
            }
        }
        return sum;
    }

    @Override
    public Iterator<Centroid> iterator() {
        return iterator(first());
    }

    private Iterator<Centroid> iterator(final int startNode) {
        return new Iterator<>() {

            int nextNode = startNode;

            @Override
            public boolean hasNext() {
                return nextNode != IntAVLTree.NIL;
            }

            @Override
            public Centroid next() {
                final Centroid next = new Centroid(mean(nextNode), count(nextNode));
                nextNode = tree.next(nextNode);
                return next;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("Read-only iterator");
            }

        };
    }

    /**
     * Return the total count of points that have been added to the tree.
     */
    public long sum() {
        return aggregatedCounts.get(tree.root());
    }

    void checkBalance() {
        tree.checkBalance(tree.root());
    }

    void checkAggregates() {
        checkAggregates(tree.root());
    }

    private void checkAggregates(int node) {
        assert aggregatedCounts.get(node) == counts.get(node) + aggregatedCounts.get(tree.left(node)) + aggregatedCounts.get(
            tree.right(node)
        );
        if (node != IntAVLTree.NIL) {
            checkAggregates(tree.left(node));
            checkAggregates(tree.right(node));
        }
    }

    @Override
    public void close() {
        Releasables.close(centroids, counts, aggregatedCounts, tree);
    }
}
