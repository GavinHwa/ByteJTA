/**
 * Copyright 2014-2016 yangming.liu<bytefox@126.com>.
 *
 * This copyrighted material is made available to anyone wishing to use, modify,
 * copy, or redistribute it subject to the terms and conditions of the GNU
 * Lesser General Public License, as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this distribution; if not, see <http://www.gnu.org/licenses/>.
 */
package org.bytesoft.bytejta.supports.dubbo.spi;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;

import org.apache.commons.lang3.StringUtils;
import org.bytesoft.bytejta.supports.dubbo.DubboRemoteCoordinator;
import org.bytesoft.bytejta.supports.dubbo.TransactionBeanRegistry;
import org.bytesoft.bytejta.supports.invoke.InvocationContext;
import org.bytesoft.bytejta.supports.rpc.TransactionRequestImpl;
import org.bytesoft.bytejta.supports.rpc.TransactionResponseImpl;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinator;
import org.bytesoft.bytejta.supports.wire.RemoteCoordinatorRegistry;
import org.bytesoft.common.utils.ByteUtils;
import org.bytesoft.transaction.Transaction;
import org.bytesoft.transaction.TransactionBeanFactory;
import org.bytesoft.transaction.TransactionContext;
import org.bytesoft.transaction.TransactionManager;
import org.bytesoft.transaction.supports.rpc.TransactionInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.rpc.Filter;
import com.alibaba.dubbo.rpc.Invocation;
import com.alibaba.dubbo.rpc.Invoker;
import com.alibaba.dubbo.rpc.Result;
import com.alibaba.dubbo.rpc.RpcContext;
import com.alibaba.dubbo.rpc.RpcException;
import com.caucho.hessian.io.HessianInput;
import com.caucho.hessian.io.HessianOutput;

public class TransactionServiceFilter implements Filter {
	static final Logger logger = LoggerFactory.getLogger(TransactionServiceFilter.class);

	public Result invoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
		if (RpcContext.getContext().isProviderSide()) {
			return this.providerInvoke(invoker, invocation);
		} else {
			return this.consumerInvoke(invoker, invocation);
		}
	}

	public Result providerInvoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
		RemoteCoordinatorRegistry remoteCoordinatorRegistry = RemoteCoordinatorRegistry.getInstance();
		TransactionBeanRegistry beanRegistry = TransactionBeanRegistry.getInstance();
		RemoteCoordinator consumeCoordinator = beanRegistry.getConsumeCoordinator();

		URL targetUrl = invoker.getUrl();
		String targetAddr = targetUrl.getIp();
		int targetPort = targetUrl.getPort();
		String address = String.format("%s:%s", targetAddr, targetPort);
		InvocationContext invocationContext = new InvocationContext();
		invocationContext.setServerHost(targetAddr);
		invocationContext.setServerPort(targetPort);

		RemoteCoordinator remoteCoordinator = remoteCoordinatorRegistry.getTransactionManagerStub(address);
		if (remoteCoordinator == null) {
			DubboRemoteCoordinator dubboCoordinator = new DubboRemoteCoordinator();
			dubboCoordinator.setInvocationContext(invocationContext);
			dubboCoordinator.setRemoteCoordinator(consumeCoordinator);

			remoteCoordinator = (RemoteCoordinator) Proxy.newProxyInstance(DubboRemoteCoordinator.class.getClassLoader(),
					new Class[] { RemoteCoordinator.class }, dubboCoordinator);
			remoteCoordinatorRegistry.putTransactionManagerStub(address, remoteCoordinator);
		}

		TransactionRequestImpl request = new TransactionRequestImpl();
		request.setTargetTransactionCoordinator(remoteCoordinator);

		TransactionResponseImpl response = new TransactionResponseImpl();
		response.setSourceTransactionCoordinator(remoteCoordinator);
		try {
			this.beforeProviderInvoke(invocation, request, response);
			return invoker.invoke(invocation);
		} catch (RpcException rex) {
			throw rex;
		} catch (RuntimeException rex) {
			logger.error("Error occurred in remote call!", rex);
			throw new RpcException(rex.getMessage());
		} finally {
			this.afterProviderInvoke(invocation, request, response);
		}

	}

	private void beforeProviderInvoke(Invocation invocation, TransactionRequestImpl request, TransactionResponseImpl response)
			throws RpcException {
		TransactionBeanRegistry beanRegistry = TransactionBeanRegistry.getInstance();
		TransactionBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		RpcException rpcError = null;
		// String transactionContextContent = RpcContext.getContext()
		// .getAttachment(TransactionContext.class.getName());
		String transactionContextContent = invocation.getAttachment(TransactionContext.class.getName());
		if (StringUtils.isNotBlank(transactionContextContent)) {
			byte[] requestByteArray = ByteUtils.stringToByteArray(transactionContextContent);
			ByteArrayInputStream bais = new ByteArrayInputStream(requestByteArray);
			HessianInput input = new HessianInput(bais);
			try {
				TransactionContext remoteTransactionContext = (TransactionContext) input.readObject();
				request.setTransactionContext(remoteTransactionContext);
			} catch (IOException ex) {
				logger.error("Error occurred in remote call!", ex);
				rpcError = new RpcException(ex.getMessage());
			}

		}

		try {
			transactionInterceptor.afterReceiveRequest(request);
		} catch (RuntimeException rex) {
			logger.error("Error occurred in remote call!", rex);
			throw new RpcException(rex.getMessage());
		}

		if (rpcError != null) {
			throw rpcError;
		}

	}

	private void afterProviderInvoke(Invocation invocation, TransactionRequestImpl request, TransactionResponseImpl response)
			throws RpcException {
		TransactionBeanRegistry beanRegistry = TransactionBeanRegistry.getInstance();
		TransactionBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();
		TransactionManager transactionManager = beanFactory.getTransactionManager();

		String transactionContextContent = invocation.getAttachment(TransactionContext.class.getName());

		Transaction transaction = transactionManager.getTransactionQuietly();
		TransactionContext nativeTransactionContext = transaction == null ? null : transaction.getTransactionContext();

		response.setTransactionContext(nativeTransactionContext);

		try {
			transactionInterceptor.beforeSendResponse(response);
			if (StringUtils.isNotBlank(transactionContextContent)) {
				ByteArrayOutputStream baos = new ByteArrayOutputStream();
				HessianOutput output = new HessianOutput(baos);
				output.writeObject(nativeTransactionContext);
				String nativeTansactionContextContent = ByteUtils.byteArrayToString(baos.toByteArray());
				// RpcContext.getContext().setAttachment(TransactionContext.class.getName(),
				// nativeTansactionContextContent);
				invocation.getAttachments().put(TransactionContext.class.getName(), nativeTansactionContextContent);
			}
		} catch (IOException ex) {
			logger.error("Error occurred in remote call!", ex);
			throw new RpcException(ex.getMessage());
		} catch (RuntimeException rex) {
			logger.error("Error occurred in remote call!", rex);
			throw new RpcException(rex.getMessage());
		}
	}

	public Result consumerInvoke(Invoker<?> invoker, Invocation invocation) throws RpcException {
		RemoteCoordinatorRegistry remoteCoordinatorRegistry = RemoteCoordinatorRegistry.getInstance();
		TransactionBeanRegistry beanRegistry = TransactionBeanRegistry.getInstance();
		TransactionBeanFactory beanFactory = beanRegistry.getBeanFactory();
		RemoteCoordinator consumeCoordinator = beanRegistry.getConsumeCoordinator();
		TransactionManager transactionManager = beanFactory.getTransactionManager();
		Transaction transaction = transactionManager.getTransactionQuietly();
		TransactionContext nativeTransactionContext = transaction == null ? null : transaction.getTransactionContext();

		URL targetUrl = invoker.getUrl();
		String targetAddr = targetUrl.getIp();
		int targetPort = targetUrl.getPort();
		String address = String.format("%s:%s", targetAddr, targetPort);
		InvocationContext invocationContext = new InvocationContext();
		invocationContext.setServerHost(targetAddr);
		invocationContext.setServerPort(targetPort);

		RemoteCoordinator remoteCoordinator = remoteCoordinatorRegistry.getTransactionManagerStub(address);
		if (remoteCoordinator == null) {
			DubboRemoteCoordinator dubboCoordinator = new DubboRemoteCoordinator();
			dubboCoordinator.setInvocationContext(invocationContext);
			dubboCoordinator.setRemoteCoordinator(consumeCoordinator);

			remoteCoordinator = (RemoteCoordinator) Proxy.newProxyInstance(DubboRemoteCoordinator.class.getClassLoader(),
					new Class[] { RemoteCoordinator.class }, dubboCoordinator);
			remoteCoordinatorRegistry.putTransactionManagerStub(address, remoteCoordinator);
		}

		TransactionRequestImpl request = new TransactionRequestImpl();
		request.setTransactionContext(nativeTransactionContext);
		request.setTargetTransactionCoordinator(remoteCoordinator);

		TransactionResponseImpl response = new TransactionResponseImpl();
		response.setSourceTransactionCoordinator(remoteCoordinator);
		try {
			this.beforeConsumerInvoke(invocation, request, response);
			return invoker.invoke(invocation);
		} catch (RpcException rex) {
			throw rex;
		} catch (RuntimeException rex) {
			logger.error("Error occurred in remote call!", rex);
			throw new RpcException(rex.getMessage());
		} finally {
			this.afterConsumerInvoke(invocation, request, response);
		}

	}

	private void beforeConsumerInvoke(Invocation invocation, TransactionRequestImpl request, TransactionResponseImpl response)
			throws RpcException {
		TransactionBeanRegistry beanRegistry = TransactionBeanRegistry.getInstance();
		TransactionBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		transactionInterceptor.beforeSendRequest(request);
		if (request.getTransactionContext() != null) {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			HessianOutput output = new HessianOutput(baos);
			try {
				output.writeObject(request.getTransactionContext());
			} catch (IOException ex) {
				logger.error("Error occurred in remote call!", ex);
				throw new RpcException(ex.getMessage());
			}
			String transactionContextContent = ByteUtils.byteArrayToString(baos.toByteArray());
			// RpcContext.getContext().setAttachment(TransactionContext.class.getName(),
			// transactionContextContent);
			invocation.getAttachments().put(TransactionContext.class.getName(), transactionContextContent);
		}
	}

	private void afterConsumerInvoke(Invocation invocation, TransactionRequestImpl request, TransactionResponseImpl response)
			throws RpcException {
		TransactionBeanRegistry beanRegistry = TransactionBeanRegistry.getInstance();
		TransactionBeanFactory beanFactory = beanRegistry.getBeanFactory();
		TransactionInterceptor transactionInterceptor = beanFactory.getTransactionInterceptor();

		RpcException rpcError = null;
		try {
			if (request.getTransactionContext() != null) {
				// String transactionContextContent = RpcContext.getContext()
				// .getAttachment(TransactionContext.class.getName());
				String transactionContextContent = invocation.getAttachment(TransactionContext.class.getName());
				byte[] byteArray = ByteUtils.stringToByteArray(transactionContextContent);
				ByteArrayInputStream bais = new ByteArrayInputStream(byteArray);
				HessianInput input = new HessianInput(bais);
				TransactionContext remoteTransactionContext = (TransactionContext) input.readObject();
				response.setTransactionContext(remoteTransactionContext);
			}
		} catch (IOException ex) {
			logger.error("Error occurred in remote call!", ex);
			rpcError = new RpcException(ex.getMessage());
		}

		try {
			transactionInterceptor.afterReceiveResponse(response);
		} catch (RuntimeException rex) {
			logger.error("Error occurred in remote call!", rex);
			throw new RpcException(rex.getMessage());
		}

		if (rpcError != null) {
			throw rpcError;
		}

	}

}
