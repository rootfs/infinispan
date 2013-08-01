/* 
 * JBoss, Home of Professional Open Source
 * Copyright 2011 Red Hat Inc. and/or its affiliates and other contributors
 * as indicated by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU Lesser General Public License, v. 2.1.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE.  See the GNU Lesser General Public License for more details.
 * You should have received a copy of the GNU Lesser General Public License,
 * v.2.1 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA  02110-1301, USA.
 */
package org.infinispan.distribution.rehash;

import org.infinispan.AdvancedCache;
import org.infinispan.commands.write.PutKeyValueCommand;
import org.infinispan.configuration.cache.CacheMode;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.distribution.MagicKey;
import org.infinispan.remoting.rpc.RpcManager;
import org.infinispan.test.MultipleCacheManagersTest;
import org.infinispan.transaction.TransactionMode;
import org.infinispan.tx.dld.ControlledRpcManager;
import org.testng.annotations.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.testng.AssertJUnit.assertEquals;
import static org.testng.AssertJUnit.assertNull;
import static org.testng.AssertJUnit.assertTrue;

/**
 * Tests data loss during state transfer when the primary owner of a key leaves during a put operation.
 *
 * @author Dan Berindei
 */
@Test(groups = "functional", testName = "distribution.rehash.NonTxPrimaryOwnerLeavingTest")
public class NonTxPrimaryOwnerLeavingTest extends MultipleCacheManagersTest {

   @Override
   protected void createCacheManagers() throws Throwable {
      ConfigurationBuilder c = new ConfigurationBuilder();
      c.clustering().cacheMode(CacheMode.DIST_SYNC);
      c.transaction().transactionMode(TransactionMode.NON_TRANSACTIONAL);

      addClusterEnabledCacheManager(c);
      addClusterEnabledCacheManager(c);
      waitForClusterToForm();
   }

   public void testPrimaryOwnerLeavingDuringPut() throws Exception {
      doTest(false);
   }

   public void testPrimaryOwnerLeavingDuringPutIfAbsent() throws Exception {
      doTest(true);
   }

   private void doTest(final boolean conditional) throws Exception {
      final AdvancedCache<Object, Object> cache0 = advancedCache(0);
      AdvancedCache<Object, Object> cache1 = advancedCache(1);

      // block remote put commands invoked from cache0
      ControlledRpcManager crm = new ControlledRpcManager(cache0.getRpcManager());
      cache0.getComponentRegistry().registerComponent(crm, RpcManager.class);
      cache0.getComponentRegistry().rewire();
      crm.blockBefore(PutKeyValueCommand.class);

      // try to put a key/value from cache0 with cache1 the primary owner
      final MagicKey key = new MagicKey(cache1);
      Future<Object> future = fork(new Callable<Object>() {
         @Override
         public Object call() throws Exception {
            return conditional ? cache0.put(key, "v") : cache0.putIfAbsent(key, "v");
         }
      });

      // after the put command was sent, kill cache1
      crm.waitForCommandToBlock();
      cache1.stop();

      // now that cache1 is stopped, unblock the put command
      crm.stopBlocking();

      // check that the put command didn't fail
      Object result = future.get(10, TimeUnit.SECONDS);
      assertNull(result);
      log.tracef("Put operation is done");

      // check the value on the remaining node
      assertEquals("v", cache0.get(key));
   }
}