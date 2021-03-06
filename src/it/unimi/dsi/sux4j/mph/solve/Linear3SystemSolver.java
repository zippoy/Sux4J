package it.unimi.dsi.sux4j.mph.solve;

/*
 * Sux4J: Succinct data structures for Java
 *
 * Copyright (C) 2015-2016 Sebastiano Vigna 
 *
 *  This library is free software; you can redistribute it and/or modify it
 *  under the terms of the GNU Lesser General Public License as published by the Free
 *  Software Foundation; either version 3 of the License, or (at your option)
 *  any later version.
 *
 *  This library is distributed in the hope that it will be useful, but
 *  WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 *  or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public License
 *  for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public License
 *  along with this program; if not, see <http://www.gnu.org/licenses/>.
 *
 */

import it.unimi.dsi.Util;
import it.unimi.dsi.bits.BitVector;
import it.unimi.dsi.bits.TransformationStrategy;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongBigList;
import it.unimi.dsi.sux4j.mph.GOV3Function;
import it.unimi.dsi.sux4j.mph.GOVMinimalPerfectHashFunction;
import it.unimi.dsi.sux4j.mph.Hashes;
import it.unimi.dsi.sux4j.mph.HypergraphSorter;

import java.util.Arrays;
import java.util.Iterator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A class implementing generation and solution of a random 3-regular linear system on <b>F</b><sub>2</sub> or  
 *  <b>F</b><sub>3</sub> using the techniques described by
 * Marco Genuzio, Giuseppe Ottaviano and Sebastiano Vigna in
 *  &ldquo;Fast Scalable Construction of (Minimal Perfect Hash) Functions&rdquo;,
 * <i>15th International Symposium on Experimental Algorithms &mdash; SEA 2016</i>, 
 * Lecture Notes in Computer Science, Springer, 2016. It
 * can be seen as a generalization of the {@linkplain HypergraphSorter 3-hypergraph edge sorting procedure}
 * that is necessary for the Majewski-Wormald-Havas-Czech technique.
 * 
 * <p>Instances of this class contain the data necessary to generate the random system
 * and solve it. At construction time, you provide just the desired number
 * of equations and variables; then, you have two possibilities:
 * <ul>
 * <li>You can call {@link #generateAndSolve(Iterable, long, LongBigList)} providing a value list;
 * it will generate
 * a random linear system on <b>F</b><sub>2</sub> with three variables per equation; the constant term for the
 * <var>k</var>-th equation will be the <var>k</var>-th element of the provided list. This kind of
 * system is useful for computing a {@link GOV3Function}.
 * <li>You can call {@link #generateAndSolve(Iterable, long, LongBigList)} with a {@code null} value list;
 * it will generate a random linear 
 * system on <b>F</b><sub>3</sub> with three variables per equation;
 * to compute the constant term, the system is viewed as a 3-hypergraph on the set of variable,
 * and it is <em>{@linkplain Orient3Hypergraph#orient(int[][], int[], int[], int[], int[], int[]) oriented}</em>&mdash;to
 * each equation with associate one of its variables, and distinct equations are associated with distinct
 * variables. The index (0, 1 or 2) of the variable associated to an equation becomes the constant part.
 * This kind of system is useful for computing a {@link GOVMinimalPerfectHashFunction}.
 * </ul>
 * 
 * <p>In both cases, the number of elements returned by the provide {@link Iterable} must
 * be equal to the number of equation passed at construction time.
 * 
 * <p>To guarantee consistent results when reading a {@link GOV3Function} or {@link GOVMinimalPerfectHashFunction},
 * the method {@link #bitVectorToEquation(BitVector, long, int, int[])} can be used to retrieve, starting from
 * a bit vector, the corresponding equation. While having a function returning the edge starting
 * from a key would be more object-oriented and avoid hidden dependencies, it would also require
 * storing the transformation provided at construction time, which would make this class non-thread-safe.
 * Just be careful to transform the keys into bit vectors using
 * the same {@link TransformationStrategy} used to generate the random linear system.
 * 
 * <h2>Support for preprocessed keys</h2>
 * 
 * <p>This class provides two special access points for classes that have pre-digested their keys. The methods
 * generation methods and {@link #tripleToEquation(long[], long, int, int[])} use
 * fixed-length 192-bit keys under the form of triples of longs. The intended usage is that of 
 * turning the keys into such a triple using {@linkplain Hashes#spooky4(BitVector,long,long[]) SpookyHash} and
 * then operating directly on the hash codes. This is particularly useful in chunked constructions, where
 * the keys are replaced by their 192-bit hashes in the first place. Note that the hashes are actually
 * rehashed using {@link Hashes#spooky4(long[], long, long[])}&mdash;this is necessary to vary the linear system
 * whenever it is unsolvable (or the associated hypergraph is not orientable).
 * 
 * <p><strong>Warning</strong>: you cannot mix the bitvector-based and the triple-based constructors and static
 * methods. It is your responsibility to pair them correctly.
 * 
 * <h2>Implementation details</h2>
 * 
 * <p>We use {@linkplain Hashes#spooky4(BitVector, long, long[]) Jenkins's SpookyHash} 
 * to compute <em>three</em> 64-bit hash values.
 * 
 * <h3>The XOR trick</h3>
 * 
 * <p>Before proceeding with the actual solution of the linear system, we perform a <em>peeling</em>
 * of the hypergraph associated with the system, which iteratively removes edges that contain a vertex
 * of degree one. Since the list of edges incident to a vertex is
 * accessed during the peeling process only when the vertex has degree one, we can actually
 * store in a single integer the XOR of the indices of all edges incident to the vertex. This approach
 * significantly simplifies the code and reduces memory usage. It is described in detail
 * in &ldquo;<a href="http://vigna.di.unimi.it/papers.php#BBOCOPRH">Cache-oblivious peeling of random hypergraphs</a>&rdquo;, by
 * Djamal Belazzougui, Paolo Boldi, Giuseppe Ottaviano, Rossano Venturini, and Sebastiano Vigna, <i>Proc.&nbsp;Data 
 * Compression Conference 2014</i>, 2014.
 * 
 * <p>We push further this idea by observing that since one of the vertices of an edge incident to <var>x</var>
 * is exactly <var>x</var>, we can even avoid storing the edges at all and just store for each vertex
 * two additional values that contain a XOR of the other two vertices of each edge incident on the node. This
 * approach further simplifies the code as every 3-hyperedge is presented to us as a distinguished vertex (the
 * hinge) plus two additional vertices.
 * 
 * <h3>Rounds and Logging</h3>
 * 
 * <P>Building and sorting a large 3-regular linear system is difficult, as
 * solving linear systems is superquadratic. This classes uses techniques introduced in the
 * paper quoted in the introduction (and in particular <em>broadword programming</em> and
 * <em>lazy Gaussian evaluation</em>) to speed up the process by orders of magnitudes.
 * 
 * <p>Note that we might generate non-orientable hypergraphs, or non-solvable systems, in which case
 * one has to try again with a different seed.
 * 
 * <P>To help diagnosing problem with the generation process, this class will {@linkplain Logger#debug(String) log at debug level} what's happening.
 *
 * @author Sebastiano Vigna
 */


public class Linear3SystemSolver<T> {
	private final static Logger LOGGER = LoggerFactory.getLogger( Linear3SystemSolver.class );
	private static final boolean ASSERTS = false;
	private static final boolean DEBUG = false;
	
	/** The initial size of the queue used to peel the 3-hypergraph. */
	private static final int INITIAL_QUEUE_SIZE = 1024;

	/** The number of vertices in the hypergraph. */
	private final int numVertices;
	/** The number of edges in the hypergraph. */
	private final int numEdges;
	/** For each vertex, the XOR of the indices of incident 3-hyperedges. */
	private final int[] edge;
	/** The hinge stack. At the end of a peeling phase, it contains the hinges in reverse order. */
	private final int[] stack;
	/** The degree of each vertex of the intermediate 3-hypergraph. */
	private final int[] d;
	/** Whether we ever called {@link #generateAndSort(Iterator, long)} or {@link #generateAndSort(Iterator, TransformationStrategy, long)}. */
	private boolean neverUsed;
	/** Initial top of the edge stack. */
	private int top;
	/** The stack used for peeling the graph. */
	private final IntArrayList visitStack;
	/** Three parallel arrays containing each one of the three vertices of a hyperedge. */ 
	private final int[][] edge2Vertex;
	/** For each edge, whether it has been peeled. */
	private final boolean[] peeled;
	/** The vector of solutions. */
	public long[] solution;
	/** The number of generated unsolvable systems. */
	public int unsolvable;
	/** The number of generated unorientable graphs. */
	public int unorientable;

	/** Creates a linear 3-regular system solver for a given number of variables and equations.
	 * 
	 * @param numVariables the number of variables.
	 * @param numEquations the number of equations.
	 */
	public Linear3SystemSolver( final int numVariables, final int numEquations ) {
		this.numVertices = numVariables;
		this.numEdges = numEquations;
		peeled = new boolean[ numEdges ];
		edge = new int[ numVertices ];
		edge2Vertex = new int[ 3 ][ numEdges ];
		stack = new int[ numEdges ];
		d = new int[ numVertices ];
		visitStack = new IntArrayList( INITIAL_QUEUE_SIZE );
		neverUsed = true;
	}
	
	private final void cleanUpIfNecessary() {
		if ( ! neverUsed ) { 
			Arrays.fill( d, 0 );
			Arrays.fill( edge, 0 );
			Arrays.fill( peeled, false );
			unorientable = unsolvable = 0;
		}
		neverUsed = false;
	}

	private final void xorEdge( final int e, final int hinge ) {
		if ( hinge != edge2Vertex[ 0 ][ e ] ) edge[ edge2Vertex[ 0 ][ e ] ] ^= e;
		if ( hinge != edge2Vertex[ 1 ][ e ] ) edge[ edge2Vertex[ 1 ][ e ] ] ^= e;
		if ( hinge != edge2Vertex[ 2 ][ e ] ) edge[ edge2Vertex[ 2 ][ e ] ] ^= e;
	}

	private final void xorEdge( final int e ) {
		edge[ edge2Vertex[ 0 ][ e ] ] ^= e;
		edge[ edge2Vertex[ 1 ][ e ] ] ^= e;
		edge[ edge2Vertex[ 2 ][ e ] ] ^= e;
	}

	/** Turns a triple of longs into an equation.
	 * 
	 * <p>If there are no variables the vector <code>e</code> will be filled with -1.
	 * 
	 * @param triple a triple of intermediate hashes.
	 * @param seed the seed for the hash function.
	 * @param numVariables the number of variables in the system.
	 * @param e an array to store the resulting equation.
	 * @see #bitVectorToEquation(BitVector, long, int, int[])
	 */
	public static void tripleToEquation( final long[] triple, final long seed, final int numVariables, final int e[] ) {
		if ( numVariables == 0 ) {
			e[ 0 ] = e[ 1 ] = e[ 2 ] = -1;
			return;
		}
		final long[] hash = new long[ 3 ];
		Hashes.spooky4( triple, seed, hash );
		e[ 0 ] = (int)( ( hash[ 0 ] & 0x7FFFFFFFFFFFFFFFL ) % numVariables );
		e[ 1 ] = (int)( ( hash[ 1 ] & 0x7FFFFFFFFFFFFFFFL ) % numVariables );
		e[ 2 ] = (int)( ( hash[ 2 ] & 0x7FFFFFFFFFFFFFFFL ) % numVariables );
	}

	/** Turns a bit vector into an equation.
	 * 
	 * <p>If there are no variables the vector <code>e</code> will be filled with -1.
	 * 
	 * @param bv a bit vector.
	 * @param seed the seed for the hash function.
	 * @param numVariables the number of variables in the system.
	 * @param e an array to store the resulting edge.
	 */
	public static void bitVectorToEquation( final BitVector bv, final long seed, final int numVariables, final int e[] ) {
		if ( numVariables == 0 ) {
			e[ 0 ] = e[ 1 ] = e[ 2 ] = -1;
			return;
		}
		final long[] hash = new long[ 3 ];
		Hashes.spooky4( bv, seed, hash );
		e[ 0 ] = (int)( ( hash[ 0 ] & 0x7FFFFFFFFFFFFFFFL ) % numVariables );
		e[ 1 ] = (int)( ( hash[ 1 ] & 0x7FFFFFFFFFFFFFFFL ) % numVariables );
		e[ 2 ] = (int)( ( hash[ 2 ] & 0x7FFFFFFFFFFFFFFFL ) % numVariables );
	}
	
	private String edge2String( final int e ) {
		return "<" + edge2Vertex[ 0 ][ e ] + "," + edge2Vertex[ 1 ][ e ] + "," + edge2Vertex[ 2 ][ e ] + ">";
	}

	/** Generates a random 3-regular linear system on <b>F</b><sub>2</sub> or <b>F</b><sub>3</sub> 
	 * and tries to solve it.
	 * 
	 * <p>The constant part is provided by {@code valueList}. If it is {@code null}, the system
	 * will be on <b>F</b><sub>3</sub> and the constant
	 * part will be obtained by orientation, otherwise the system will be on <b>F</b><sub>2</sub>. 
	 * 
	 * @param iterable an iterable returning triples of longs.
	 * @param seed a 64-bit random seed.
	 * @param valueList a value list containing the constant part, or {@code null} if the
	 * constant part should be computed by orientation. 
	 * @return true if a solution was found.
	 * @see Linear3SystemSolver
	 */
	public boolean generateAndSolve( final Iterable<long[]> iterable, final long seed, final LongBigList valueList ) {
		// We cache all variables for faster access
		final int[] d = this.d;
		final int[] edge2Vertex0 = edge2Vertex[ 0 ], edge2Vertex1 = edge2Vertex[ 1 ], edge2Vertex2 = edge2Vertex[ 2 ];

		cleanUpIfNecessary();
		
		/* We build the edge list and compute the degree of each vertex. */
		final int[] e = new int[ 3 ];
		final Iterator<long[]> iterator = iterable.iterator();
		for( int i = 0; i < numEdges; i++ ) {
			tripleToEquation( iterator.next(), seed, numVertices, e );
			if ( DEBUG ) System.err.println("Edge <" + e[ 0 ] + "," + e[ 1 ] + "," + e[ 2 ] + ">" );
			d[ edge2Vertex0[ i ] = e[ 0 ] ]++;
			d[ edge2Vertex1[ i ] = e[ 1 ] ]++;
			d[ edge2Vertex2[ i ] = e[ 2 ] ]++;
			xorEdge( i );
		}

		if ( iterator.hasNext() ) throw new IllegalStateException( "This " + Linear3SystemSolver.class.getSimpleName() + " has " + numEdges + " edges, but the provided iterator returns more" );

		return solve( valueList );
	}

	/** Sorts the edges of a random 3-hypergraph in &ldquo;leaf peeling&rdquo; order.
	 * 
	 * @return true if the sorting procedure succeeded.
	 */
	private boolean sort() {
		// We cache all variables for faster access
		final int[] d = this.d;
		//System.err.println("Visiting...");
		if ( LOGGER.isDebugEnabled() ) LOGGER.debug( "Peeling hypergraph (" + numVertices + " vertices, " + numEdges + " edges)..." );

		top = 0;
		for( int i = 0; i < numVertices; i++ ) if ( d[ i ] == 1 ) peel( i );

		if ( top == numEdges ) {
			if ( LOGGER.isDebugEnabled() ) LOGGER.debug( "Peeling completed." );
			return true;
		}
		
		if ( LOGGER.isDebugEnabled() ) LOGGER.debug( "Peeled " + top + " edges out of " + numEdges + "." );
		return false;
	}

	private void peel( final int x ) {
		// System.err.println( "Visiting " + x + "..." );
		final int[] edge = this.edge;
		final int[] stack = this.stack;
		final int[] d = this.d;
		final IntArrayList visitStack = this.visitStack;
		
		// Stack initialization
		int v;
		visitStack.clear();
		visitStack.push( x );

		final int[] edge2Vertex0 = edge2Vertex[ 0 ];
		final int[] edge2Vertex1 = edge2Vertex[ 1 ];
		final int[] edge2Vertex2 = edge2Vertex[ 2 ];
		
		while ( ! visitStack.isEmpty() ) {
			v = visitStack.popInt();
			if ( d[ v ] == 1 ) {
				stack[ top++ ] = v;
				// System.err.println( "Stripping <" + v + ", " + vertex1[ v ] + ", " + vertex2[ v ] + ">" );
				final int e = edge[ v ];
				peeled[ e ] = true;
				xorEdge( e, v );
				if ( --d[ edge2Vertex0[ e ] ] == 1 ) visitStack.add( edge2Vertex0[ e ] );
				if ( --d[ edge2Vertex1[ e ] ] == 1 ) visitStack.add( edge2Vertex1[ e ] );
				if ( --d[ edge2Vertex2[ e ] ] == 1 ) visitStack.add( edge2Vertex2[ e ] );
			}
		}
	}
	
	private boolean solve( final LongBigList valueList ) {
		final boolean peelingCompleted = sort();
		solution = new long[ numVertices ];
		final long[] solution = this.solution;
		final int[] edge2Vertex0 = edge2Vertex[ 0 ], edge2Vertex1 = edge2Vertex[ 1 ], edge2Vertex2 = edge2Vertex[ 2 ], edge = this.edge, d = this.d;

		if ( valueList != null ) {

			if ( ! peelingCompleted ) {

				final int[][] vertex2Edge = new int[ d.length ][];
				for( int i = vertex2Edge.length; i-- != 0; ) vertex2Edge[ i ] = new int[ d[ i ] ];
				final int[] p = new int[ d.length ];
				final long[] c = new long[ d.length - top ];
				Arrays.fill( d, 0 );
				
				for ( int i = 0, j = 0; i < numEdges; i++ ) {
					if ( ! peeled[ i ] ) {
						final int v0 = edge2Vertex0[ i ];
						vertex2Edge[ v0 ][ p[ v0 ]++ ] = j;
						final int v1 = edge2Vertex1[ i ];
						vertex2Edge[ v1 ][ p[ v1 ]++ ] = j;
						final int v2 = edge2Vertex2[ i ];
						vertex2Edge[ v2 ][ p[ v2 ]++ ] = j;

						c[ j++ ] = valueList.getLong( i );
					}
				}
				
				if ( ! Modulo2System.lazyGaussianElimination( vertex2Edge, c, Util.identity( numVertices ), solution ) ) {
					unsolvable++;
					if ( LOGGER.isDebugEnabled() ) LOGGER.debug( "System is unsolvable" );
					return false;
				}
			}

			// Complete with peeled hyperedges
			while( top > 0 ) {
				final int x = stack[ --top ];
				final int e = edge[ x ];
				solution[ x ] = valueList.getLong( e );
				if ( x != edge2Vertex0[ e ] ) solution[ x ] ^= solution[ edge2Vertex0[ e ] ];
				if ( x != edge2Vertex1[ e ] ) solution[ x ] ^= solution[ edge2Vertex1[ e ] ];
				if ( x != edge2Vertex2[ e ] ) solution[ x ] ^= solution[ edge2Vertex2[ e ] ];
				
				assert valueList.getLong( e ) == ( solution[ edge2Vertex0[ e ] ] ^ solution[ edge2Vertex1[ e ] ] ^ solution[ edge2Vertex2[ e ] ] ) :
					edge2String( e ) + ": " + valueList.getLong( e ) + " != " + ( solution[ edge2Vertex0[ e ] ] ^ solution[ edge2Vertex1[ e ] ] ^ solution[ edge2Vertex2[ e ] ] );
			}
				
			return true;
		}
		else {
			if ( ! peelingCompleted ) {
				final int nonPeeled = numEdges - top;
				final int[] remEdge2Vertex0 = new int[ nonPeeled ], remEdge2Vertex1 = new int[ nonPeeled ], remEdge2Vertex2 = new int[ nonPeeled ]; 
				final int[][] edges = new int[ d.length ][];
				final boolean[] peeled = this.peeled;
				for( int i = edges.length; i-- != 0; ) edges[ i ] = new int[ d[ i ] ];
				Arrays.fill( d, 0 );

				// Compress the edge representation eliminating peeled edges.
				for ( int i = 0, j = 0; i < numEdges; i++ ) {
					if ( ! peeled[ i ] ) {
						final int v0 = edge2Vertex0[ i ];
						remEdge2Vertex0[ j ] = v0;
						edges[ v0 ][ d[ v0 ]++ ] = j;
						final int v1 = edge2Vertex1[ i ];
						remEdge2Vertex1[ j ] = v1;
						edges[ v1 ][ d[ v1 ]++ ] = j;
						final int v2 = edge2Vertex2[ i ];
						remEdge2Vertex2[ j ] = v2;
						edges[ v2 ][ d[ v2 ]++ ] = j;

						j++;
					}
				}

				final int[] hinge = new int[ nonPeeled ];
				if ( ! Orient3Hypergraph.orient( edges, d, remEdge2Vertex0, remEdge2Vertex1, remEdge2Vertex2, hinge ) ) {
					if ( LOGGER.isDebugEnabled() ) LOGGER.debug( "Hypergraph cannot be oriented" );
					unorientable++;
					return false;
				}
				
				if ( DEBUG ) for( int i = 0; i < nonPeeled; i++ ) System.err.println( "<" + remEdge2Vertex0[ i ] + "," + remEdge2Vertex1[ i ] + "," + remEdge2Vertex2[ i ] + "> => " + hinge[ i ] );

				final int[] c = new int[ nonPeeled ];
				for ( int i = 0; i < nonPeeled; i++ ) {
					final int h = hinge[ i ];
					c[ i ] = h == remEdge2Vertex0[ i ] ? 0 : h == remEdge2Vertex1[ i ] ? 1 : 2;
					assert c[ i ] != 0 || remEdge2Vertex0[ i ] == hinge[ i ];
					assert c[ i ] != 1 || remEdge2Vertex1[ i ] == hinge[ i ];
					assert c[ i ] != 2 || remEdge2Vertex2[ i ] == hinge[ i ];
				}
				
				if ( ! Modulo3System.lazyGaussianElimination( edges, c, hinge, solution ) ) {
					unsolvable++;
					if ( LOGGER.isDebugEnabled() ) LOGGER.debug( "System is unsolvable" );
					return false;
				}


				if ( ASSERTS ) {
					for( int i = 0; i < nonPeeled; i++ ) {
						final int k = hinge[ i ] == remEdge2Vertex0[ i ] ? 0 : hinge[ i ] == remEdge2Vertex1[ i ] ? 1 : 2;
						assert k != 0 || remEdge2Vertex0[ i ] == hinge[ i ];
						assert k != 1 || remEdge2Vertex1[ i ] == hinge[ i ];
						assert k != 2 || remEdge2Vertex2[ i ] == hinge[ i ];
						assert ( k == ( solution[ remEdge2Vertex0[ i ] ] + solution[ remEdge2Vertex1[ i ] ] + solution[ remEdge2Vertex2[ i ] ] ) % 3 ) :
							"<" + remEdge2Vertex0[ i ] + "," + remEdge2Vertex1[ i ] + "," + remEdge2Vertex2[ i ] + ">: " + k + " != " + ( solution[ remEdge2Vertex0[ i ] ] + solution[ remEdge2Vertex1[ i ] ] + solution[ remEdge2Vertex2[ i ] ] ) % 3;
					}
				}
				
				for( int i = 0; i < nonPeeled; i++ ) if ( solution[ hinge[ i ] ] == 0 ) solution[ hinge[ i ] ] = 3;
			}

			if ( DEBUG ) System.err.println( "Peeled (" + top + "): " );

			// Complete with peeled hyperedges
			while ( top > 0 ) {
				final int v = stack[ --top ];
				final int e = edge[ v ];
				int k;
				if ( v == edge2Vertex0[ e ] ) k = 0;
				else if ( v == edge2Vertex1[ e ] ) k = 1;
				else k = 2; 

				assert v == edge2Vertex0[ e ] || v == edge2Vertex1[ e ] || v == edge2Vertex2[ e ] : v + " not in " + edge2String( e );
				
				if ( DEBUG ) System.err.println( edge2String( e ) + " => @" + k );  

				assert solution[ v ] == 0;

				long s = 0;
				if ( v != edge2Vertex0[ e ] ) s += solution[ edge2Vertex0[ e ] ];
				if ( v != edge2Vertex1[ e ] ) s += solution[ edge2Vertex1[ e ] ];
				if ( v != edge2Vertex2[ e ] ) s += solution[ edge2Vertex2[ e ] ];

				s = ( k - s + 9 ) % 3;
				solution[ v ] = s == 0 ? 3 : s;

				assert k == ( solution[ edge2Vertex0[ e ] ] + solution[ edge2Vertex1[ e ] ] + solution[ edge2Vertex2[ e ] ] ) % 3 :
					edge2String( e ) + ": " + k + " != " + ( solution[ edge2Vertex0[ e ] ] + solution[ edge2Vertex1[ e ] ] + solution[ edge2Vertex2[ e ] ] ) % 3 ;
			}
			
			return true;
		}
	}
	
}
