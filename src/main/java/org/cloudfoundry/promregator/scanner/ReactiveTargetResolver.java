package org.cloudfoundry.promregator.scanner;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.cloudfoundry.client.v2.applications.ApplicationResource;
import org.cloudfoundry.client.v2.applications.ListApplicationsResponse;
import org.cloudfoundry.promregator.cfaccessor.CFAccessor;
import org.cloudfoundry.promregator.config.Target;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class ReactiveTargetResolver implements TargetResolver {

	@Autowired
	private CFAccessor cfAccessor;
	
	@Override
	public List<ResolvedTarget> resolveTargets(List<Target> configTargets) {
		
		Flux<ResolvedTarget> resultFlux = Flux.fromIterable(configTargets)
			/* It would be possible to add .parallel() here
			 * However, the current memory footprint (i.e. memory throughput)
			 * is already a challenge for the garbage collector (see also
			 * issue #54). That is why we currently refrain from also running
			 * the requests here in parallel. 
			 * If measurements in the future show that this would not be a problem,
			 * we are still capable of introducing this later.
			 */
			.flatMap(configTarget -> this.resolveSingleTarget(configTarget));
		
		return resultFlux.collectList().block();
	}
	
	public Flux<ResolvedTarget> resolveSingleTarget(Target configTarget) {
		if (configTarget.getApplicationName() != null) {
			// config target is already resolved
			ResolvedTarget rt = new ResolvedTarget(configTarget);
			return Flux.just(rt);
		}
		
		Mono<String> orgIdMono = this.cfAccessor.retrieveOrgId(configTarget.getOrgName())
				.map( r -> r.getResources())
				.map( l -> l.get(0))
				.map( e -> e.getMetadata()) 
				.map( entry -> entry.getId());
		
		Mono<String> spaceIdMono = orgIdMono.flatMap(orgId -> {
			return this.cfAccessor.retrieveSpaceId(orgId, configTarget.getSpaceName());
		}).map( r -> r.getResources())
			.map( l -> l.get(0))
			.map( e -> e.getMetadata())
			.map( entry -> entry.getId());
		
		Flux<String> applicationNamesFlux = selectApplications(configTarget, orgIdMono, spaceIdMono);

		Flux<ResolvedTarget> result = applicationNamesFlux
			.map(appName -> {
				ResolvedTarget newTarget = new ResolvedTarget(configTarget);
				newTarget.setApplicationName(appName);
				
				return newTarget;
			});
		
		return result;
	}

	private Flux<String> selectApplications(Target configTarget, Mono<String> orgIdMono, Mono<String> spaceIdMono) {
		/* NB: Now we have to consider three cases:
		 * Case 1: both applicationName and applicationRegex is empty => select all apps
		 * Case 2: applicationName is null, but applicationRegex is filled => filter all apps with the regex
		 * Case 3: applicationName is filled, but applicationRegeix is null => select a single app
		 * In cases 1 and 2, we need the list of all apps in the space.
		 */
		
		Flux<String> applicationsInSelection = null;
		
		if (configTarget.getApplicationRegex() == null && configTarget.getApplicationName() != null) {
			// Case 3
			Mono<ListApplicationsResponse> responseMono = Mono.zip(orgIdMono, spaceIdMono)
				.flatMap( tuple -> this.cfAccessor.retrieveApplicationId(tuple.getT1(), tuple.getT2(), configTarget.getApplicationName()) );
			
			applicationsInSelection = responseMono.map( r -> r.getResources() )
				.flatMapMany( resources -> {
					List<String> appNames = new LinkedList<>();
					for (ApplicationResource ar : resources) {
						appNames.add(ar.getEntity().getName());
					}
					return Flux.fromIterable(appNames);
				});
			
		} else {
			// Case 1 & 2: Get all apps from space
			Mono<ListApplicationsResponse> responseMono = Mono.zip(orgIdMono, spaceIdMono)
				.flatMap( tuple -> this.cfAccessor.retrieveAllApplicationIdsInSpace(tuple.getT1(), tuple.getT2()));
			
			applicationsInSelection = responseMono.map( r -> r.getResources())
				.flatMapMany(resources -> {
					List<String> appNames = new LinkedList<>();
					for (ApplicationResource ar : resources) {
						if (!isApplicationInScrapableState(ar.getEntity().getState())) {
							continue;
						}
						
						appNames.add(ar.getEntity().getName());
					}
					
					return Flux.fromIterable(appNames);
				});
		}

		Flux<String> filteredApplicationsInSpace = applicationsInSelection;
		if (configTarget.getApplicationRegex() != null) {
			// Case 2
			final Pattern filterPattern = Pattern.compile(configTarget.getApplicationRegex());
			
			filteredApplicationsInSpace = applicationsInSelection.filter(appName -> {
				Matcher m = filterPattern.matcher(appName);
				return m.matches();
			});
		}

		return filteredApplicationsInSpace;
	}
	
	
	
	private boolean isApplicationInScrapableState(String state) {
		if ("STARTED".equals(state)) {
			return true;
		}
		
		/* TODO: To be enhanced, once we know of further states, which are
		 * also scrapable.
		 */
		
		return false;
	}
}